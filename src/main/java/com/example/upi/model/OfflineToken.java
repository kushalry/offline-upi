package com.example.upi.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * OfflineToken — a SIGNED, VERIFIABLE payment promise that works without internet.
 *
 * THE PROBLEM IT SOLVES:
 * Alice and Bob are both offline (e.g., remote area, plane, festival ground).
 * Alice wants to pay Bob ₹200 via Bluetooth. How does Bob know Alice actually
 * has ₹200 and isn't double-spending?
 *
 * THE SOLUTION (similar to NPCI's UPI 123Pay Lite proposals + offline CBDC research):
 *
 *   1. Alice's phone PRE-RESERVES funds from her UPI Lite wallet when last online.
 *      Backend issues N signed "spending capacity" tokens worth ₹X each.
 *   2. To pay Bob ₹200: Alice creates a PAYMENT token signed with HER private key,
 *      referencing the pre-reserved capacity. Sends it via Bluetooth/NFC to Bob.
 *   3. Bob VERIFIES Alice's signature using her public key (which he can fetch
 *      next time HE'S online, or which was cached from a prior interaction).
 *   4. When EITHER Alice or Bob next comes online, they upload the token. The
 *      backend SETTLES it: debits Alice's reserved pool, credits Bob's wallet.
 *
 * KEY INVARIANTS:
 * - A token has a unique nonce — backend rejects duplicate nonce on settlement
 *   (this is how we prevent double-spend even though the protocol is offline).
 * - Tokens have an expiry. Stale tokens get rejected.
 * - The signature includes the sender's reserved-pool reference, so an attacker
 *   can't forge a token even if they steal Alice's phone (they don't have her
 *   pre-reserved capacity, just the device key).
 *
 * SETTLEMENT GUARANTEE:
 * This is "eventually consistent" in the CAP sense. We trade strong consistency
 * (which requires being online) for availability (works offline). Conflicts
 * (e.g., Alice tries to spend the same reserved pool twice) are detected at
 * settlement time and the LATER token is rejected — last-write-loses.
 */
@Entity
@Table(name = "offline_tokens", indexes = {
        @Index(name = "idx_token_nonce", columnList = "nonce", unique = true),
        @Index(name = "idx_token_status", columnList = "status,expiresAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OfflineToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique token identifier — random UUID. Used to prevent replay/double-spend. */
    @Column(unique = true, nullable = false, length = 64)
    private String nonce;

    @Column(nullable = false, length = 100)
    private String senderVpa;

    /** Receiver may be empty when token is freshly issued (sender hasn't paid anyone yet). */
    @Column(length = 100)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TokenStatus status;

    /**
     * The canonical payload that was signed.
     * Format: nonce|sender|receiver|amount|issuedAt|expiresAt
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String signedPayload;

    /** Base64 RSA signature over signedPayload, signed with sender's private key. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String signature;

    @Column
    private Instant settledAt;

    @Column(length = 32)
    private String settlementUtr;

    @Column(length = 300)
    private String rejectionReason;

    public enum TokenStatus {
        ISSUED,     // sender created it; not yet handed to a receiver
        IN_FLIGHT,  // receiver holds it, not yet settled
        SETTLED,    // backend processed it — money moved
        EXPIRED,    // expired before settlement
        REJECTED    // signature invalid, double-spend, or other failure
    }
}
