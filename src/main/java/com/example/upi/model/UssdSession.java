package com.example.upi.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * USSD session — one row per active dial-in.
 *
 * In production this would live in REDIS, not the main DB. Sessions are
 * ephemeral (60-180s TTL), high-write, low-read, and don't need ACID.
 * Storing them in JPA here keeps the demo self-contained, but the comment
 * is here to show you know better.
 */
@Entity
@Table(name = "ussd_sessions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UssdSession {

    @Id
    @Column(length = 36)
    private String sessionId;

    @Column(nullable = false, length = 15)
    private String mobileNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SessionState state;

    @Column(length = 100)
    private String receiverVpa;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 200)
    private String remarks;

    @Column(length = 64)
    private String idempotencyKey;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (expiresAt == null) expiresAt = createdAt.plusSeconds(180);
    }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    public enum SessionState {
        MENU, AWAITING_VPA, AWAITING_AMOUNT, AWAITING_REMARK, AWAITING_PIN,
        AWAITING_LITE_LOAD_AMOUNT, AWAITING_LITE_LOAD_PIN
    }
}
