package com.example.upi.service;

import com.example.upi.dto.Dtos.*;
import com.example.upi.exception.Exceptions.*;
import com.example.upi.model.Account;
import com.example.upi.model.Transaction;
import com.example.upi.model.Transaction.TxnChannel;
import com.example.upi.model.Transaction.TxnStatus;
import com.example.upi.repository.AccountRepository;
import com.example.upi.repository.TransactionRepository;
import com.example.upi.util.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The transactional core. Handles online UPI, USSD-mediated transfers, and
 * settlement of UPI Lite + offline tokens.
 *
 * IDEMPOTENCY MODEL:
 * Every transfer must carry an idempotencyKey. We look up by key first; if
 * found, we return the prior result without doing anything. This makes
 * client retries safe and is essential when the network is flaky.
 *
 * Note: only SUCCESS rows preserve the client's idempotency key. FAILED rows
 * (audit log) get a synthetic key — otherwise a one-time PIN typo would lock
 * the user out of retrying with that key forever.
 *
 * CONCURRENCY MODEL:
 * Account.@Version triggers OptimisticLockException under concurrent updates.
 * The {@code transferWithRetry} method demonstrates this — Spring Retry
 * auto-retries on the exception with exponential backoff.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepo;
    private final TransactionRepository txnRepo;
    private final TransactionAuditLogger auditLogger;

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRED)
    public TransferResponse transfer(TransferRequest req, TxnChannel channel) {

        // 1. IDEMPOTENCY — only SUCCESS rows are checked here (failures use synthetic keys)
        var existing = txnRepo.findByIdempotencyKey(req.getIdempotencyKey());
        if (existing.isPresent()) {
            Transaction t = existing.get();
            log.info("Idempotent replay detected for key={}, returning cached result", req.getIdempotencyKey());
            return TransferResponse.builder()
                    .utr(t.getUtr())
                    .status(t.getStatus().name())
                    .message(t.getStatus() == TxnStatus.SUCCESS ? "Transfer completed" : t.getFailureReason())
                    .idempotentReplay(true)
                    .build();
        }

        // 2. Look up parties
        Account sender = accountRepo.findByVpa(req.getSenderVpa())
                .orElseThrow(() -> new AccountNotFoundException("Sender VPA not found"));
        Account receiver = accountRepo.findByVpa(req.getReceiverVpa())
                .orElseThrow(() -> new AccountNotFoundException("Receiver VPA not found"));

        if (sender.getId().equals(receiver.getId()))
            throw new IllegalArgumentException("Cannot transfer to self");

        // 3. PIN check — for ONLINE/USSD only (UPI Lite spend doesn't need PIN)
        if (channel == TxnChannel.ONLINE || channel == TxnChannel.USSD_OFFLINE) {
            if (!Hashing.matches(req.getUpiPin(), sender.getPinHash())) {
                auditLogger.logFailure(req, channel, "Invalid UPI PIN");
                throw new InvalidPinException();
            }
        }

        BigDecimal amount = req.getAmount();

        // 4. Channel-specific limits
        if (channel == TxnChannel.USSD_OFFLINE && amount.compareTo(sender.getOfflineLimitPerTxn()) > 0) {
            auditLogger.logFailure(req, channel, "Exceeds offline per-txn limit");
            throw new LimitExceededException("Amount exceeds offline limit of ₹" + sender.getOfflineLimitPerTxn());
        }

        // 5. Balance check & debit/credit
        if (channel == TxnChannel.UPI_LITE || channel == TxnChannel.P2P_OFFLINE) {
            if (sender.getUpiLiteBalance().compareTo(amount) < 0) {
                auditLogger.logFailure(req, channel, "Insufficient UPI Lite balance");
                throw new InsufficientBalanceException("Insufficient UPI Lite balance");
            }
            sender.setUpiLiteBalance(sender.getUpiLiteBalance().subtract(amount));
            receiver.setUpiLiteBalance(receiver.getUpiLiteBalance().add(amount));
        } else {
            if (sender.getBalance().compareTo(amount) < 0) {
                auditLogger.logFailure(req, channel, "Insufficient balance");
                throw new InsufficientBalanceException("Insufficient balance");
            }
            sender.setBalance(sender.getBalance().subtract(amount));
            receiver.setBalance(receiver.getBalance().add(amount));
        }

        accountRepo.saveAndFlush(sender);
        accountRepo.saveAndFlush(receiver);

        Transaction txn = Transaction.builder()
                .utr(generateUtr())
                .idempotencyKey(req.getIdempotencyKey())
                .senderVpa(sender.getVpa())
                .receiverVpa(receiver.getVpa())
                .amount(amount)
                .status(TxnStatus.SUCCESS)
                .channel(channel)
                .remarks(req.getRemarks())
                .settledAt(Instant.now())
                .build();
        txnRepo.save(txn);

        log.info("Transfer SUCCESS: {} -> {} ₹{} via {} (UTR: {})",
                sender.getVpa(), receiver.getVpa(), amount, channel, txn.getUtr());

        BigDecimal newBalance = (channel == TxnChannel.UPI_LITE || channel == TxnChannel.P2P_OFFLINE)
                ? sender.getUpiLiteBalance() : sender.getBalance();

        return TransferResponse.builder()
                .utr(txn.getUtr())
                .status("SUCCESS")
                .message("₹" + amount + " sent to " + receiver.getVpa())
                .newBalance(newBalance)
                .idempotentReplay(false)
                .build();
    }

    /**
     * Wraps {@link #transfer} with auto-retry on optimistic-lock conflicts.
     * Use from controllers when you expect contention.
     */
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public TransferResponse transferWithRetry(TransferRequest req, TxnChannel channel) {
        return transfer(req, channel);
    }

    @Transactional
    public TransferResponse loadUpiLite(String vpa, LiteLoadRequest req) {
        var existing = txnRepo.findByIdempotencyKey(req.getIdempotencyKey());
        if (existing.isPresent()) {
            Transaction t = existing.get();
            return TransferResponse.builder()
                    .utr(t.getUtr())
                    .status(t.getStatus().name())
                    .idempotentReplay(true).build();
        }

        Account acc = accountRepo.findByVpa(vpa)
                .orElseThrow(() -> new AccountNotFoundException("VPA not found"));
        if (!Hashing.matches(req.getUpiPin(), acc.getPinHash())) {
            throw new InvalidPinException();
        }
        BigDecimal amount = req.getAmount();
        if (acc.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient bank balance to load Lite");
        }
        BigDecimal newLite = acc.getUpiLiteBalance().add(amount);
        if (newLite.compareTo(acc.getUpiLiteMaxBalance()) > 0) {
            throw new LimitExceededException("UPI Lite cap is ₹" + acc.getUpiLiteMaxBalance());
        }

        acc.setBalance(acc.getBalance().subtract(amount));
        acc.setUpiLiteBalance(newLite);
        accountRepo.saveAndFlush(acc);

        Transaction txn = Transaction.builder()
                .utr(generateUtr())
                .idempotencyKey(req.getIdempotencyKey())
                .senderVpa(vpa).receiverVpa(vpa)
                .amount(amount)
                .status(TxnStatus.SUCCESS)
                .channel(TxnChannel.UPI_LITE)
                .remarks("UPI Lite top-up")
                .settledAt(Instant.now())
                .build();
        txnRepo.save(txn);

        log.info("UPI Lite loaded: {} ₹{} (new lite balance: ₹{})", vpa, amount, newLite);
        return TransferResponse.builder()
                .utr(txn.getUtr()).status("SUCCESS")
                .message("₹" + amount + " loaded to UPI Lite")
                .newBalance(newLite).build();
    }

    private String generateUtr() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
