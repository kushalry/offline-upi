package com.example.upi.service;

import com.example.upi.dto.Dtos.*;
import com.example.upi.exception.Exceptions.*;
import com.example.upi.model.Account;
import com.example.upi.model.OfflineToken;
import com.example.upi.model.OfflineToken.TokenStatus;
import com.example.upi.model.Transaction.TxnChannel;
import com.example.upi.repository.AccountRepository;
import com.example.upi.repository.OfflineTokenRepository;
import com.example.upi.util.Hashing;
import com.example.upi.util.RsaCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * P2P OFFLINE TOKEN ENGINE
 *
 * Three operations:
 *
 *   ISSUE  — sender (currently online) creates a signed token. Their UPI Lite
 *            wallet is debited immediately to RESERVE the funds. The token can
 *            then be transmitted via Bluetooth/NFC/QR to a receiver who is
 *            also offline.
 *
 *   VERIFY — anyone (including the offline receiver) can verify the token's
 *            signature using the sender's public key. This proves authenticity
 *            without contacting the backend. (Receiver caches the sender's
 *            public key when last online, or fetches it lazily.)
 *
 *   REDEEM — when EITHER party next comes online, they upload the token. The
 *            backend re-verifies the signature, checks the nonce hasn't been
 *            used, then credits the receiver's UPI Lite wallet.
 *
 * DOUBLE-SPEND PREVENTION:
 *   - Sender's funds are reserved AT ISSUE TIME (debited from upiLiteBalance).
 *     They literally cannot issue a second token they couldn't honor.
 *   - Each token has a unique nonce. The DB has a UNIQUE constraint on it.
 *     If a malicious sender somehow generates two tokens with the same nonce,
 *     one is rejected at INSERT time.
 *   - Tokens have an expiresAt. The reconciliation sweeper expires stale tokens
 *     (and refunds the sender's reserved balance — see {@link #expireStaleTokens()}).
 *
 * WHY THIS BEATS NAIVE APPROACHES:
 *   - "Send a balance update over Bluetooth" — receiver has no way to verify the
 *     sender actually has the funds; trivially forgeable.
 *   - "Have receiver call the backend to confirm" — defeats the offline goal.
 *   - "Trust on first use + retry sync" — works until two payments race.
 *
 * Our model is the same primitive used in offline CBDC research and Visa's
 * Offline Payments pilot: signed pre-funded tokens with deferred settlement.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OfflineTokenService {

    private static final long TOKEN_VALIDITY_HOURS = 24;

    private final OfflineTokenRepository tokenRepo;
    private final AccountRepository accountRepo;
    private final TransactionService txnService;
    private final AccountService accountService;

    /**
     * ISSUE phase. Sender is online. We:
     *   1. Verify their PIN
     *   2. Reserve funds from upiLiteBalance
     *   3. Sign the token with their device key
     *   4. Return the signed payload — the device hands it off via BT/NFC/QR
     */
    @Transactional
    public OfflineTokenPayload issue(IssueTokenRequest req) {
        Account sender = accountRepo.findByVpa(req.getSenderVpa())
                .orElseThrow(() -> new AccountNotFoundException("Sender not found"));
        // Validate receiver exists too — issuing to a non-existent VPA is pointless
        accountRepo.findByVpa(req.getReceiverVpa())
                .orElseThrow(() -> new AccountNotFoundException("Receiver not found"));

        if (!Hashing.matches(req.getUpiPin(), sender.getPinHash())) {
            throw new InvalidPinException();
        }

        if (req.getAmount().compareTo(sender.getOfflineLimitPerTxn()) > 0) {
            throw new LimitExceededException("Token amount exceeds offline per-txn limit");
        }
        if (sender.getUpiLiteBalance().compareTo(req.getAmount()) < 0) {
            throw new InsufficientBalanceException("Insufficient UPI Lite balance to back this token");
        }

        // RESERVE funds — debit from UPI Lite. If token expires, this is refunded.
        sender.setUpiLiteBalance(sender.getUpiLiteBalance().subtract(req.getAmount()));
        accountRepo.saveAndFlush(sender);

        // Build canonical payload — order matters for signature verification
        String nonce = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(TOKEN_VALIDITY_HOURS * 3600);
        String canonical = String.join("|",
                nonce,
                req.getSenderVpa(),
                req.getReceiverVpa(),
                req.getAmount().toPlainString(),
                now.toString(),
                exp.toString());

        // Sign with the sender's device private key
        String privateKeyB64 = accountService.getDevicePrivateKey(req.getSenderVpa());
        var privateKey = RsaCrypto.decodePrivateKey(privateKeyB64);
        String signature = RsaCrypto.sign(canonical, privateKey);

        // Persist the token record (status = IN_FLIGHT — already handed off conceptually)
        OfflineToken stored = OfflineToken.builder()
                .nonce(nonce)
                .senderVpa(req.getSenderVpa())
                .receiverVpa(req.getReceiverVpa())
                .amount(req.getAmount())
                .issuedAt(now)
                .expiresAt(exp)
                .status(TokenStatus.IN_FLIGHT)
                .signedPayload(canonical)
                .signature(signature)
                .build();
        tokenRepo.save(stored);

        log.info("Issued offline token nonce={} from={} to={} amount=₹{}",
                nonce, req.getSenderVpa(), req.getReceiverVpa(), req.getAmount());

        return OfflineTokenPayload.builder()
                .nonce(nonce)
                .senderVpa(req.getSenderVpa())
                .receiverVpa(req.getReceiverVpa())
                .amount(req.getAmount())
                .issuedAt(now)
                .expiresAt(exp)
                .signedPayload(canonical)
                .signature(signature)
                .build();
    }

    /**
     * VERIFY phase. The receiver's device runs this locally with the cached
     * sender public key. We expose it as a backend endpoint too for testing.
     */
    public boolean verifyOffline(OfflineTokenPayload tok, String senderPublicKeyBase64) {
        try {
            // 1. Re-derive canonical payload — must match what was signed
            String expectedCanonical = String.join("|",
                    tok.getNonce(),
                    tok.getSenderVpa(),
                    tok.getReceiverVpa(),
                    tok.getAmount().toPlainString(),
                    tok.getIssuedAt().toString(),
                    tok.getExpiresAt().toString());
            if (!expectedCanonical.equals(tok.getSignedPayload())) {
                log.warn("Token canonical mismatch — payload was tampered");
                return false;
            }

            // 2. Check expiry
            if (Instant.now().isAfter(tok.getExpiresAt())) {
                log.warn("Token expired at {}", tok.getExpiresAt());
                return false;
            }

            // 3. Verify signature against sender public key
            PublicKey pk = RsaCrypto.decodePublicKey(senderPublicKeyBase64);
            return RsaCrypto.verify(expectedCanonical, tok.getSignature(), pk);
        } catch (Exception e) {
            log.warn("Offline verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * REDEEM phase. Either party comes online and uploads the token.
     * We re-verify, check nonce uniqueness (= no double-spend), and settle.
     */
    @Transactional
    public RedeemTokenResponse redeem(OfflineTokenPayload tok) {
        // 1. Look up sender to get their public key
        Account sender = accountRepo.findByVpa(tok.getSenderVpa())
                .orElseThrow(() -> new AccountNotFoundException("Sender not found"));

        // 2. Verify signature server-side. NEVER trust client verification alone.
        if (!verifyOffline(tok, sender.getPublicKeyBase64())) {
            throw new TokenVerificationException("Token signature verification failed");
        }

        // 3. Idempotency / double-spend check via nonce
        var existing = tokenRepo.findByNonce(tok.getNonce());
        if (existing.isPresent()) {
            OfflineToken e = existing.get();
            if (e.getStatus() == TokenStatus.SETTLED) {
                log.info("Token already settled — returning prior result for nonce={}", tok.getNonce());
                return RedeemTokenResponse.builder()
                        .status("ALREADY_SETTLED")
                        .utr(e.getSettlementUtr())
                        .message("Token was already redeemed").build();
            }
            if (e.getStatus() == TokenStatus.REJECTED || e.getStatus() == TokenStatus.EXPIRED) {
                throw new TokenVerificationException("Token previously rejected: " + e.getRejectionReason());
            }
        }

        // 4. Settle. Note: sender's funds were ALREADY debited at issue time.
        //    Settlement just credits the receiver. This avoids a double-debit
        //    if the network blips during settlement (idempotent on the sender side).
        Account receiver = accountRepo.findByVpa(tok.getReceiverVpa())
                .orElseThrow(() -> new AccountNotFoundException("Receiver not found"));

        BigDecimal newReceiverLite = receiver.getUpiLiteBalance().add(tok.getAmount());
        // Receiver's UPI Lite cap may overflow on incoming — in real UPI, excess auto-spills to bank balance
        if (newReceiverLite.compareTo(receiver.getUpiLiteMaxBalance()) > 0) {
            BigDecimal overflow = newReceiverLite.subtract(receiver.getUpiLiteMaxBalance());
            receiver.setUpiLiteBalance(receiver.getUpiLiteMaxBalance());
            receiver.setBalance(receiver.getBalance().add(overflow));
            log.info("Receiver Lite cap exceeded; ₹{} overflowed to bank balance", overflow);
        } else {
            receiver.setUpiLiteBalance(newReceiverLite);
        }
        accountRepo.saveAndFlush(receiver);

        // Update token record
        OfflineToken stored = existing.orElseGet(() -> OfflineToken.builder()
                .nonce(tok.getNonce())
                .senderVpa(tok.getSenderVpa())
                .receiverVpa(tok.getReceiverVpa())
                .amount(tok.getAmount())
                .issuedAt(tok.getIssuedAt())
                .expiresAt(tok.getExpiresAt())
                .signedPayload(tok.getSignedPayload())
                .signature(tok.getSignature())
                .build());

        String utr = "OFL" + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        stored.setStatus(TokenStatus.SETTLED);
        stored.setSettledAt(Instant.now());
        stored.setSettlementUtr(utr);
        tokenRepo.save(stored);

        log.info("REDEEMED token nonce={} sender={} receiver={} amount=₹{} utr={}",
                tok.getNonce(), tok.getSenderVpa(), tok.getReceiverVpa(), tok.getAmount(), utr);

        return RedeemTokenResponse.builder()
                .status("SETTLED")
                .utr(utr)
                .message("Offline token settled. ₹" + tok.getAmount() + " credited to " + tok.getReceiverVpa())
                .build();
    }

    /**
     * Reconciliation sweeper — runs every minute. Expires unsettled tokens and
     * REFUNDS the sender's reserved balance. Without this, expired tokens would
     * leave funds locked forever.
     *
     * In production this would be a separate job, possibly across multiple workers
     * with leader election (e.g., Quartz cluster, ShedLock, or Kubernetes CronJob).
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleTokens() {
        List<OfflineToken> stale = tokenRepo.findByStatusInAndExpiresAtBefore(
                List.of(TokenStatus.IN_FLIGHT, TokenStatus.ISSUED), Instant.now());
        if (stale.isEmpty()) return;

        log.info("Sweeper: expiring {} stale tokens", stale.size());
        for (OfflineToken t : stale) {
            // Refund sender
            Account sender = accountRepo.findByVpa(t.getSenderVpa()).orElse(null);
            if (sender != null) {
                sender.setUpiLiteBalance(sender.getUpiLiteBalance().add(t.getAmount()));
                accountRepo.saveAndFlush(sender);
            }
            t.setStatus(TokenStatus.EXPIRED);
            t.setRejectionReason("Token expired before settlement");
            tokenRepo.save(t);
            log.info("Expired token nonce={} refunded ₹{} to {}", t.getNonce(), t.getAmount(), t.getSenderVpa());
        }
    }
}
