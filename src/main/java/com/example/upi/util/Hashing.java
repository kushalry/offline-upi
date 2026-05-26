package com.example.upi.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Wrapper around Spring Security's BCryptPasswordEncoder.
 * BCrypt is preferred over plain SHA because:
 *  - Adaptive cost: increase work factor as hardware improves
 *  - Per-hash random salt baked in
 *  - Resistant to GPU-based brute force (memory-hard variants like Argon2 are even better)
 */
public final class Hashing {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    private Hashing() {}
    public static String hash(String plain) { return ENCODER.encode(plain); }
    public static boolean matches(String plain, String hashed) { return ENCODER.matches(plain, hashed); }
}
