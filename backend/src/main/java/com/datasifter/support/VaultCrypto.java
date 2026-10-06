package com.datasifter.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.ServiceLoader;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class VaultCrypto {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_DERIVATION = "PBKDF2WithHmacSHA256";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final Path DATA_DIRECTORY = JsonFileStore.resolveDataDirectory();
    private static final Path SALT_FILE = DATA_DIRECTORY.resolve("vault-master.salt");
    private static final Path LEGACY_KEY_FILE = DATA_DIRECTORY.resolve("vault-master.key");
    private static final ThreadLocal<KeyMaterial> OVERRIDE_KEY = new ThreadLocal<>();
    private static final ReentrantReadWriteLock KEY_LOCK = new ReentrantReadWriteLock();
    private static volatile KeyMaterial currentKey;
    private static volatile VaultKeyProvider managedProvider;

    private VaultCrypto() {
    }

    public static synchronized void initialize(String passphrase) {
        initialize(passphrase, "local", false);
    }

    public static synchronized void initialize(
            String passphrase, String providerId, boolean requireManagedProvider) {
        KEY_LOCK.writeLock().lock();
        try {
            managedProvider = null;
            currentKey = null;
            if (providerId == null || !providerId.matches("[A-Za-z0-9._-]{1,128}")) {
                throw new IllegalStateException("Vault key provider ID has an invalid format");
            }
            if ("local".equals(providerId)) {
                if (requireManagedProvider) {
                    throw new IllegalStateException("A managed vault key provider is required in this environment");
                }
                managedProvider = null;
                currentKey = derive(passphrase, readOrCreateSalt());
                return;
            }
            VaultKeyProvider selectedProvider = ServiceLoader.load(VaultKeyProvider.class).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(provider -> providerId.equals(provider.providerId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Configured vault key provider is not installed: " + providerId));
            String keyReference = selectedProvider.activeKeyReference();
            if (keyReference == null || keyReference.isBlank() || keyReference.length() > 2048) {
                throw new IllegalStateException("Managed vault key provider returned an invalid key reference");
            }
            KeyMaterial legacyKey = passphrase == null || passphrase.isBlank()
                    ? null
                    : derive(passphrase, readOrCreateSalt());
            managedProvider = selectedProvider;
            currentKey = legacyKey;
        } finally {
            KEY_LOCK.writeLock().unlock();
        }
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        KEY_LOCK.readLock().lock();
        try {
            if (managedProvider != null) {
                return encryptWithManagedProvider(plaintext, managedProvider);
            }
            KeyMaterial key = activeKey();
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key.key(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return "v2:" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt secret value", e);
        } finally {
            KEY_LOCK.readLock().unlock();
        }
    }

    public static String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isEmpty()) {
            return encryptedValue;
        }
        KEY_LOCK.readLock().lock();
        try {
            boolean versionTwo = encryptedValue.startsWith("v2:");
            if (encryptedValue.startsWith("v3:")) {
                return decryptWithManagedProvider(encryptedValue);
            }
            String encoded = versionTwo ? encryptedValue.substring(3) : encryptedValue;
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= IV_LENGTH_BYTES + GCM_TAG_LENGTH_BITS / 8) {
                throw new IllegalArgumentException("Encrypted secret payload is too short");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    versionTwo ? currentKey().key() : legacyKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("Unable to decrypt secret value; vault key is invalid or data is corrupt", e);
        } finally {
            KEY_LOCK.readLock().unlock();
        }
    }

    public static synchronized void rotatePassphrase(
            String newPassphrase,
            Runnable rewriteSecrets,
            Runnable restoreSecrets) {
        if (managedProvider != null) {
            throw new IllegalStateException(
                    "Vault key rotation is managed by the configured key provider; rotate it with that provider");
        }
        validatePassphrase(newPassphrase);
        byte[] newSalt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(newSalt);
        KeyMaterial newKey = derive(newPassphrase, newSalt);
        KeyMaterial previousKey = currentKey;
        if (previousKey == null) {
            throw new IllegalStateException("Vault must be initialized before passphrase rotation");
        }

        KEY_LOCK.writeLock().lock();
        try {
            OVERRIDE_KEY.set(newKey);
            try {
                rewriteSecrets.run();
                writeSalt(newSalt);
                currentKey = newKey;
            } catch (RuntimeException failure) {
                OVERRIDE_KEY.set(previousKey);
                try {
                    restoreSecrets.run();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                try {
                    writeSalt(previousKey.salt());
                    currentKey = previousKey;
                } catch (RuntimeException saltRestoreFailure) {
                    failure.addSuppressed(saltRestoreFailure);
                }
                throw new IllegalStateException("Vault passphrase rotation failed; rollback was attempted", failure);
            }
        } finally {
            OVERRIDE_KEY.remove();
            KEY_LOCK.writeLock().unlock();
        }
    }

    public static boolean isManagedProviderActive() {
        return managedProvider != null;
    }

    private static String encryptWithManagedProvider(String plaintext, VaultKeyProvider provider) {
        byte[] dataKey = new byte[KEY_LENGTH_BITS / 8];
        new SecureRandom().nextBytes(dataKey);
        try {
            String keyReference = provider.activeKeyReference();
            if (keyReference == null || keyReference.isBlank() || keyReference.length() > 2048) {
                throw new GeneralSecurityException("Managed provider returned an invalid key reference");
            }
            byte[] wrappedKey = provider.wrapKey(keyReference, dataKey);
            if (wrappedKey == null || wrappedKey.length == 0) {
                throw new GeneralSecurityException("Managed provider returned an empty wrapped key");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            String keyId = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(keyReference.getBytes(StandardCharsets.UTF_8));
            String aad = "v3:" + provider.providerId() + ":" + keyId;
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return "v3:" + provider.providerId() + ":" + keyId + ":"
                    + Base64.getEncoder().encodeToString(wrappedKey) + ":"
                    + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt secret with managed vault key provider", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    private static String decryptWithManagedProvider(String encryptedValue) {
        try {
            String[] parts = encryptedValue.split(":", 5);
            if (parts.length != 5 || managedProvider == null
                    || !managedProvider.providerId().equals(parts[1])) {
                throw new IllegalArgumentException("Encrypted value does not match the configured key provider");
            }
            String keyReference = new String(
                    Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            if (keyReference.isBlank() || keyReference.length() > 2048) {
                throw new IllegalArgumentException("Encrypted secret has an invalid key reference");
            }
            byte[] wrappedKey = Base64.getDecoder().decode(parts[3]);
            byte[] payload = Base64.getDecoder().decode(parts[4]);
            if (wrappedKey.length == 0 || payload.length <= IV_LENGTH_BYTES + GCM_TAG_LENGTH_BITS / 8) {
                throw new IllegalArgumentException("Encrypted secret payload is too short");
            }
            byte[] dataKey = managedProvider.unwrapKey(keyReference, wrappedKey);
            if (dataKey == null) {
                throw new GeneralSecurityException("Managed provider returned no data key");
            }
            try {
                if (dataKey.length != KEY_LENGTH_BITS / 8) {
                    throw new GeneralSecurityException("Managed provider returned an invalid data key");
                }
                byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH_BYTES);
                byte[] ciphertext = Arrays.copyOfRange(payload, IV_LENGTH_BYTES, payload.length);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                        new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
                cipher.updateAAD(("v3:" + managedProvider.providerId() + ":" + parts[2])
                        .getBytes(StandardCharsets.UTF_8));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("Unable to decrypt secret with managed vault key provider", e);
        }
    }

    private static KeyMaterial activeKey() {
        KeyMaterial override = OVERRIDE_KEY.get();
        KeyMaterial key = override == null ? currentKey : override;
        if (key == null) {
            throw new IllegalStateException("Vault is not initialized; configure DATASIFTER_VAULT_PASSPHRASE");
        }
        return key;
    }

    private static KeyMaterial currentKey() {
        KeyMaterial key = currentKey;
        if (key == null) {
            throw new IllegalStateException("Vault is not initialized; configure DATASIFTER_VAULT_PASSPHRASE");
        }
        return key;
    }

    private static KeyMaterial derive(String passphrase, byte[] salt) {
        validatePassphrase(passphrase);
        char[] password = passphrase.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance(KEY_DERIVATION).generateSecret(spec).getEncoded();
            try {
                return new KeyMaterial(new SecretKeySpec(key, "AES"), salt.clone());
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to derive vault encryption key", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
        }
    }

    private static void validatePassphrase(String passphrase) {
        if (passphrase == null || passphrase.length() < 12 || passphrase.length() > 1024) {
            throw new IllegalStateException("Vault passphrase must contain between 12 and 1024 characters");
        }
    }

    private static byte[] readOrCreateSalt() {
        try {
            if (Files.exists(SALT_FILE)) {
                byte[] salt = Base64.getDecoder().decode(Files.readString(SALT_FILE, StandardCharsets.UTF_8).trim());
                if (salt.length != SALT_LENGTH_BYTES) {
                    throw new IllegalStateException("Vault salt file has an invalid length");
                }
                return salt;
            }
            byte[] salt = new byte[SALT_LENGTH_BYTES];
            new SecureRandom().nextBytes(salt);
            writeSalt(salt);
            return salt;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to read or initialize vault salt", e);
        }
    }

    private static void writeSalt(byte[] salt) {
        Path temporary = null;
        try {
            Files.createDirectories(DATA_DIRECTORY);
            temporary = Files.createTempFile(DATA_DIRECTORY, ".vault-salt-", ".tmp");
            Files.writeString(temporary, Base64.getEncoder().encodeToString(salt), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, SALT_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, SALT_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist vault salt", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to clean up temporary vault salt file", e);
                }
            }
        }
    }

    private static SecretKeySpec legacyKey() throws GeneralSecurityException {
        try {
            if (Files.notExists(LEGACY_KEY_FILE)) {
                throw new IllegalStateException("Legacy vault key is missing; legacy secrets cannot be decrypted");
            }
            byte[] raw = Base64.getDecoder().decode(Files.readString(LEGACY_KEY_FILE, StandardCharsets.UTF_8).trim());
            if (raw.length != KEY_LENGTH_BITS / 8) {
                throw new IllegalStateException("Legacy vault key has an invalid length");
            }
            try {
                return new SecretKeySpec(raw, "AES");
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to load legacy vault key", e);
        }
    }

    private record KeyMaterial(SecretKeySpec key, byte[] salt) {
    }
}
