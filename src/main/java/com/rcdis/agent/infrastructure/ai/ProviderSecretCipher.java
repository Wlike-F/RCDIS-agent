package com.rcdis.agent.infrastructure.ai;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.ModelProviderProperties;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Protects model provider API keys at rest.
 *
 * <p>Values are encrypted with AES-256/GCM using a fresh random 12 byte IV per key and stored as
 * {@code v1:<base64 iv>:<base64 ciphertext+tag>}. The version prefix keeps the scheme rotatable.
 * The master key is derived from {@code rcdis.ai.secret-key}
 * (environment variable {@code RCDIS_PROVIDER_SECRET_KEY}); when it is absent a development fallback
 * is used and a warning is logged, so plaintext keys never reach the database either way.</p>
 */
@Slf4j
@Component
public class ProviderSecretCipher {

    private static final String CIPHER_PREFIX = "v1:";
    private static final String KEY_ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String SHA_256 = "SHA-256";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MASK_VISIBLE_CHARS = 4;
    private static final int MASK_MIN_LENGTH = 12;
    private static final String MASK = "****";

    /** Keeps local development bootable when no master passphrase is configured. */
    private static final String DEV_FALLBACK_PASSPHRASE = "rcdis-agent-development-provider-secret-key";

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /** True when {@code rcdis.ai.secret-key} was supplied explicitly. */
    @Getter
    private final boolean explicitSecretKey;

    public ProviderSecretCipher(ModelProviderProperties properties) {
        String passphrase = properties.getSecretKey();
        this.explicitSecretKey = StringUtils.hasText(passphrase);
        if (!this.explicitSecretKey) {
            passphrase = DEV_FALLBACK_PASSPHRASE;
            log.atWarn().log("rcdis.ai.secret-key is not set; provider API keys are encrypted with a development "
                    + "fallback key. Set RCDIS_PROVIDER_SECRET_KEY before deploying.");
        }
        this.secretKey = new SecretKeySpec(sha256(passphrase), KEY_ALGORITHM);
    }

    /**
     * Encrypts a plaintext API key.
     *
     * @return the versioned ciphertext, or {@code null} when the input is blank
     */
    public String encrypt(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return CIPHER_PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt model provider API key", exception);
        }
    }

    /**
     * Decrypts a stored API key.
     *
     * <p>Values without the {@code v1:} prefix are returned unchanged so that keys seeded from
     * {@code application.yml} as plain environment values keep working.</p>
     */
    public String decrypt(String storedValue) {
        if (!StringUtils.hasText(storedValue)) {
            return null;
        }
        if (!isEncrypted(storedValue)) {
            return storedValue;
        }
        String[] parts = storedValue.split(":");
        if (parts.length != 3) {
            throw new BusinessException(
                    "MODEL_PROVIDER_SECRET_INVALID",
                    "Stored API key ciphertext is malformed; expected format v1:<iv>:<ciphertext>");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // Most likely the master passphrase changed after the key was stored.
            throw new BusinessException(
                    "MODEL_PROVIDER_SECRET_DECRYPT_FAILED",
                    "Failed to decrypt the stored API key. Verify that RCDIS_PROVIDER_SECRET_KEY matches the value "
                            + "used when the key was saved, then re-enter the API key.",
                    exception);
        }
    }

    public boolean isEncrypted(String storedValue) {
        return storedValue != null && storedValue.startsWith(CIPHER_PREFIX);
    }

    /**
     * Builds a display hint that reveals at most the last four characters of a key.
     */
    public String mask(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return null;
        }
        String value = plainText.trim();
        if (value.length() <= MASK_MIN_LENGTH) {
            return MASK;
        }
        return MASK + value.substring(value.length() - MASK_VISIBLE_CHARS);
    }

    private static byte[] sha256(String passphrase) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return digest.digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
