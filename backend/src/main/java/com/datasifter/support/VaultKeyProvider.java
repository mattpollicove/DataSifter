package com.datasifter.support;

import java.security.GeneralSecurityException;

public interface VaultKeyProvider {
    String providerId();

    String activeKeyReference();

    byte[] wrapKey(String keyReference, byte[] dataKey) throws GeneralSecurityException;

    byte[] unwrapKey(String keyReference, byte[] wrappedKey) throws GeneralSecurityException;
}
