package com.rcdis.agent.infrastructure.feishu;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.util.HashUtils;
import com.rcdis.agent.config.FeishuProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cryptographic helpers for inbound Feishu callbacks in HTTP mode.
 *
 * <p>Two independent protections are applied:</p>
 * <ul>
 *   <li><b>Confidentiality</b> — when an Encrypt Key is configured Feishu posts
 *       {@code {"encrypt":"<base64>"}}. The payload is AES-256-CBC where the key is the raw
 *       SHA-256 of the Encrypt Key and the first 16 bytes of the decoded value are the IV.</li>
 *   <li><b>Authenticity</b> — {@code X-Lark-Signature} must equal
 *       {@code sha256(timestamp + nonce + encryptKey + rawBody)} in lowercase hex, where rawBody is
 *       the encrypted request body as received.</li>
 * </ul>
 *
 * <p>The WebSocket long-connection mode needs neither: the SDK authenticates the channel with the
 * app credentials, which is why the dispatcher is built with empty token and key arguments.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuCallbackCrypto {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String SHA_256 = "SHA-256";
    private static final int IV_LENGTH_BYTES = 16;

    private final FeishuProperties properties;

    public boolean isEncryptionEnabled() {
        return StringUtils.hasText(properties.getEncryptKey());
    }

    public boolean isSignatureVerifiable() {
        return StringUtils.hasText(properties.getEncryptKey());
    }

    /**
     * Decrypts a base64 AES-256-CBC payload produced by Feishu.
     */
    public String decrypt(String encryptedBase64) {
        if (!isEncryptionEnabled()) {
            throw new IllegalStateException(
                    "Cannot decrypt a Feishu callback because rcdis.feishu.encrypt-key is not configured");
        }
        if (!StringUtils.hasText(encryptedBase64)) {
            throw new IllegalArgumentException("Encrypted Feishu callback body must not be blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptedBase64.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Feishu callback encrypt field is not valid base64", exception);
        }
        if (decoded.length <= IV_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "Feishu callback ciphertext is too short to contain an IV and a payload");
        }
        byte[] iv = Arrays.copyOfRange(decoded, 0, IV_LENGTH_BYTES);
        byte[] cipherText = Arrays.copyOfRange(decoded, IV_LENGTH_BYTES, decoded.length);
        try {
            SecretKeySpec key = new SecretKeySpec(sha256Raw(properties.getEncryptKey()), KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            // Almost always means the configured Encrypt Key does not match the application's.
            throw new IllegalStateException(
                    "Failed to decrypt the Feishu callback body. Verify that rcdis.feishu.encrypt-key matches "
                            + "the Encrypt Key of the application.",
                    exception);
        }
    }

    /**
     * Verifies the {@code X-Lark-Signature} header against the raw request body.
     *
     * @return true when the signature matches, or when signing cannot be verified because no Encrypt
     *         Key is configured and the caller chose to accept unsigned callbacks
     */
    public boolean verifySignature(String timestamp, String nonce, String rawBody, String signature) {
        if (!StringUtils.hasText(properties.getEncryptKey())) {
            log.atWarn().log("Feishu callback signature was not verified because rcdis.feishu.encrypt-key is unset");
            return false;
        }
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            return false;
        }
        String expected = HashUtils.sha256Hex(
                timestamp + nonce + properties.getEncryptKey() + (rawBody == null ? "" : rawBody));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks the Verification Token that Feishu echoes in every callback header.
     */
    public boolean verifyToken(String token) {
        String expected = properties.getVerificationToken();
        if (!StringUtils.hasText(expected)) {
            log.atWarn().log("Feishu verification token was not checked because rcdis.feishu.verification-token is unset");
            return false;
        }
        return StringUtils.hasText(token) && MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256Raw(String value) {
        try {
            return MessageDigest.getInstance(SHA_256).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
