package com.example.upi.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Lightweight PIN hashing. In production use BCrypt/Argon2.
 * Format stored: base64(salt) + ":" + base64(sha256(salt+pin))
 */
public class PinHasher {

    private static final SecureRandom RNG = new SecureRandom();

    public static String hash(String pin) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] hash = sha256(concat(salt, pin.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean matches(String pin, String stored) {
        try {
            String[] parts = stored.split(":");
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = sha256(concat(salt, pin.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, actual); // constant-time compare
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
