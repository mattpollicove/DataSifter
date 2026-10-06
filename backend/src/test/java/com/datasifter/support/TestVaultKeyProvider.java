package com.datasifter.support;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class TestVaultKeyProvider implements VaultKeyProvider {
    private static final int IV_LENGTH = 12;
    private static final SecretKeySpec WRAPPING_KEY = new SecretKeySpec(new byte[32], "AES");

    @Override
    public String providerId() {
        return "test";
    }

    @Override
    public String activeKeyReference() {
        return "test-key-v1";
    }

    @Override
    public byte[] wrapKey(String keyReference, byte[] dataKey) throws GeneralSecurityException {
        return crypt(Cipher.ENCRYPT_MODE, keyReference, dataKey);
    }

    @Override
    public byte[] unwrapKey(String keyReference, byte[] wrappedKey) throws GeneralSecurityException {
        return crypt(Cipher.DECRYPT_MODE, keyReference, wrappedKey);
    }

    private byte[] crypt(int mode, String keyReference, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv;
        byte[] payload;
        if (mode == Cipher.ENCRYPT_MODE) {
            iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            payload = input;
        } else {
            if (input.length <= IV_LENGTH) {
                throw new GeneralSecurityException("Wrapped key is too short");
            }
            iv = java.util.Arrays.copyOfRange(input, 0, IV_LENGTH);
            payload = java.util.Arrays.copyOfRange(input, IV_LENGTH, input.length);
        }
        cipher.init(mode, WRAPPING_KEY, new GCMParameterSpec(128, iv));
        cipher.updateAAD(keyReference.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(payload);
        if (mode == Cipher.DECRYPT_MODE) {
            return ciphertext;
        }
        return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
    }
}
