package com.datasifter.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VaultCryptoTest {
    private static final String PASSPHRASE = "test-vault-passphrase-25";

    @AfterEach
    void restoreTestPassphrase() {
        VaultCrypto.initialize(PASSPHRASE);
    }

    @Test
    void encryptsWithVersionedCiphertextAndRejectsTampering() {
        VaultCrypto.initialize(PASSPHRASE);
        String encrypted = VaultCrypto.encrypt("secret-data");
        assertEquals("v2:", encrypted.substring(0, 3));
        assertEquals("secret-data", VaultCrypto.decrypt(encrypted));
        assertThrows(IllegalStateException.class, () -> VaultCrypto.decrypt(encrypted.substring(0, encrypted.length() - 2) + "AA"));
    }

    @Test
    void rejectsWrongPassphraseAndSupportsExplicitRotation() {
        VaultCrypto.initialize(PASSPHRASE);
        String encrypted = VaultCrypto.encrypt("rotate-me");
        VaultCrypto.initialize("different-vault-passphrase");
        assertThrows(IllegalStateException.class, () -> VaultCrypto.decrypt(encrypted));

        VaultCrypto.initialize(PASSPHRASE);
        String[] storedValue = {encrypted};
        VaultCrypto.rotatePassphrase(
                "rotated-vault-passphrase-25",
                () -> storedValue[0] = VaultCrypto.encrypt(VaultCrypto.decrypt(storedValue[0])),
                () -> storedValue[0] = encrypted);
        assertEquals("rotate-me", VaultCrypto.decrypt(storedValue[0]));
    }

    @Test
    void restoresOldCiphertextAndSaltWhenRotationFails() {
        VaultCrypto.initialize(PASSPHRASE);
        String[] storedValue = {VaultCrypto.encrypt("preserve-me")};
        assertThrows(IllegalStateException.class, () -> VaultCrypto.rotatePassphrase(
                "failed-rotation-passphrase",
                () -> {
                    storedValue[0] = VaultCrypto.encrypt("partially-rotated");
                    throw new IllegalStateException("simulated repository write failure");
                },
                () -> storedValue[0] = VaultCrypto.encrypt("preserve-me")));
        assertEquals("preserve-me", VaultCrypto.decrypt(storedValue[0]));
    }

    @Test
    void usesManagedProviderEnvelopeEncryptionAndFailsClosedWhenProviderIsRequired() {
        assertThrows(IllegalStateException.class, () -> VaultCrypto.initialize("", "local", true));

        VaultCrypto.initialize("", "test", true);
        String encrypted = VaultCrypto.encrypt("managed-secret");
        assertTrue(encrypted.startsWith("v3:test:"));
        assertEquals("managed-secret", VaultCrypto.decrypt(encrypted));
        assertThrows(IllegalStateException.class, () -> VaultCrypto.rotatePassphrase(
                PASSPHRASE, () -> {}, () -> {}));
    }
}
