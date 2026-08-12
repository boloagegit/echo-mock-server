package com.echo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM encryption for reversible JMS credentials stored in the application database. */
@Component
@Slf4j
public final class JmsCredentialCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String INSECURE_DEFAULT = "change-me-in-production";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public JmsCredentialCipher(Environment environment) {
        String configuredKey = environment.getProperty("echo.jms.credential-key");
        if (configuredKey == null || configuredKey.isBlank()) {
            configuredKey = environment.getProperty("echo.remember-me.key", INSECURE_DEFAULT);
        }
        if (INSECURE_DEFAULT.equals(configuredKey)) {
            log.warn("JMS credential encryption is using the default key; set ECHO_JMS_CREDENTIAL_KEY");
        }
        this.key = new SecretKeySpec(sha256(configuredKey), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt JMS credential", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("Unsupported JMS credential format");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalStateException("Invalid JMS credential payload");
            }
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Unable to decrypt JMS credential; verify ECHO_JMS_CREDENTIAL_KEY", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
