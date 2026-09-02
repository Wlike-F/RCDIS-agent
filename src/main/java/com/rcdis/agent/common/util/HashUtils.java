package com.rcdis.agent.common.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HashUtils {

    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private HashUtils() {
        throw new UnsupportedOperationException("HashUtils cannot be instantiated");
    }

    public static String sha256Hex(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    public static String hmacSha256Hex(String value, String secret) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
        return hmacSha256Hex(value.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Hex(byte[] bytes, byte[] secret) {
        byte[] digest = hmacSha256(bytes, secret);
        return HexFormat.of().formatHex(digest);
    }

    public static String hmacSha256Base64(byte[] bytes, byte[] secret) {
        byte[] digest = hmacSha256(bytes, secret);
        return Base64.getEncoder().encodeToString(digest);
    }

    private static byte[] hmacSha256(byte[] bytes, byte[] secret) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 algorithm is not available", exception);
        }
    }
}
