package com.example.upi.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The Account entity represents a user's bank account + UPI handle.
 *
 * KEY DESIGN DECISIONS:
 *
 * 1. {@code @Version} field: enables OPTIMISTIC LOCKING.
 *    When two transactions update the same account concurrently, the second
 *    commit will fail with OptimisticLockException. This is critical for
 *    a payments system — without it, simultaneous debits can cause "lost updates"
 *    where one transfer's balance change is silently overwritten.
 *
 *    We chose optimistic over pessimistic locking because:
 *    - Payments are short-lived (millis) — contention is rare
 *    - Pessimistic locks (SELECT ... FOR UPDATE) hurt throughput at scale
 *    - On conflict, we retry the transaction (see TransactionService)
 *
 * 2. Separate online vs offline wallets:
 *    {@code balance} = funds in the actual bank account (online UPI debits this)
 *    {@code upiLiteBalance} = pre-loaded on-device wallet for offline txns
 *    Real UPI Lite limits: ₹2000 wallet, ₹500/txn, no PIN required for spending
 *
 * 3. Public key for offline P2P:
 *    Each user device generates an RSA keypair on registration. The public
 *    key is uploaded so other users can verify signed offline tokens.
 */
@Entity
@Table(name = "accounts", indexes = {
        @Index(name = "idx_account_vpa", columnList = "vpa", unique = true),
        @Index(name = "idx_account_mobile", columnList = "mobileNumber", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic locking version. Hibernate auto-increments on update; mismatched version on commit throws OptimisticLockException. */
    @Version
    private Long version;

    @Column(unique = true, nullable = false, length = 15)
    private String mobileNumber;

    @Column(unique = true, nullable = false, length = 100)
    private String vpa;

    @Column(nullable = false, length = 100)
    private String accountHolderName;

    @Column(nullable = false, length = 20)
    private String bankAccountNumber;

    @Column(nullable = false, length = 11)
    private String ifscCode;

    /** BCrypt-hashed UPI PIN. BCrypt is adaptive — work factor scales with hardware. */
    @Column(nullable = false)
    private String pinHash;

    /** BCrypt-hashed login password (separate from UPI PIN). */
    @Column(nullable = false)
    private String passwordHash;

    /** Bank account balance (the "real" money). */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    /** UPI Lite wallet balance — pre-loaded, used for offline txns, no PIN needed up to limit. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal upiLiteBalance;

    /** NPCI's actual UPI Lite per-txn limit is ₹500. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal offlineLimitPerTxn;

    /** NPCI's actual UPI Lite max wallet is ₹2000. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal upiLiteMaxBalance;

    /** Base64-encoded RSA public key — used to verify signed offline tokens this user issued. */
    @Column(columnDefinition = "TEXT")
    private String publicKeyBase64;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (balance == null) balance = BigDecimal.ZERO;
        if (upiLiteBalance == null) upiLiteBalance = BigDecimal.ZERO;
        if (offlineLimitPerTxn == null) offlineLimitPerTxn = new BigDecimal("500.00");
        if (upiLiteMaxBalance == null) upiLiteMaxBalance = new BigDecimal("2000.00");
    }
}
