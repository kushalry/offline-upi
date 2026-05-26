package com.example.upi.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transaction log — append-only ledger of every transfer attempt.
 *
 * KEY DESIGN DECISIONS:
 *
 * 1. {@code idempotencyKey} (UNIQUE): the client supplies this on every
 *    transfer request. If the same key arrives twice (network retry,
 *    USSD double-dial, etc.) we return the first result instead of
 *    debiting twice. This is the SAME pattern Stripe and every other
 *    serious payments API use.
 *
 * 2. We log FAILURES too. A failed PIN attempt or insufficient-balance
 *    error is recorded with status=FAILED. Auditors and fraud detection
 *    need this trail.
 *
 * 3. {@code channel} disambiguates online vs offline. Reconciliation,
 *    reporting, and rate limiting all key off this.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_utr", columnList = "utr", unique = true),
        @Index(name = "idx_txn_idempotency", columnList = "idempotencyKey", unique = true),
        @Index(name = "idx_txn_sender", columnList = "senderVpa,createdAt"),
        @Index(name = "idx_txn_receiver", columnList = "receiverVpa,createdAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UPI Unique Transaction Reference — what shows on bank statements. */
    @Column(unique = true, nullable = false, length = 32)
    private String utr;

    /** Client-supplied idempotency key — prevents duplicate processing. */
    @Column(unique = true, nullable = false, length = 64)
    private String idempotencyKey;

    @Column(nullable = false, length = 100)
    private String senderVpa;

    @Column(nullable = false, length = 100)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TxnStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TxnChannel channel;

    @Column(length = 200)
    private String remarks;

    @Column(length = 300)
    private String failureReason;

    /**
     * For P2P offline tokens: the token's signature.
     * Lets auditors prove the receiver actually held a valid token at txn time.
     */
    @Column(columnDefinition = "TEXT")
    private String offlineTokenSignature;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant settledAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public enum TxnStatus {
        PENDING,        // accepted, not yet settled (deferred-settlement txns sit here)
        SUCCESS,        // settled, money moved
        FAILED,         // rejected — see failureReason
        REVERSED        // settled then reversed (e.g. dispute, fraud)
    }

    public enum TxnChannel {
        ONLINE,         // standard UPI: bank-to-bank, debits balance
        USSD_OFFLINE,   // *99# flow — user phone offline, gateway online
        UPI_LITE,       // on-device wallet, deferred settlement
        P2P_OFFLINE     // signed offline token between two devices
    }
}
