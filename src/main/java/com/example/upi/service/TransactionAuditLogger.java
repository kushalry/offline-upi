package com.example.upi.service;

import com.example.upi.dto.Dtos.TransferRequest;
import com.example.upi.model.Transaction;
import com.example.upi.model.Transaction.TxnChannel;
import com.example.upi.model.Transaction.TxnStatus;
import com.example.upi.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Separate bean so {@code @Transactional(REQUIRES_NEW)} works correctly.
 *
 * Spring's AOP proxy intercepts calls FROM OTHER BEANS. Self-invocation
 * (this.method()) bypasses the proxy and the annotation is silently ignored.
 * That's a classic Spring footgun — putting the failure-logging method in
 * its own bean ensures the proxy actually wraps the call.
 *
 * IMPORTANT: failure rows do NOT carry the original idempotency key.
 * If we did, a user retrying after a failure would be stuck — the idempotency
 * check would return the failure forever. Failures get a synthetic key so
 * retries can succeed.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionAuditLogger {

    private final TransactionRepository txnRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(TransferRequest req, TxnChannel channel, String reason) {
        String syntheticUtr = "FAIL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Transaction failed = Transaction.builder()
                .utr(syntheticUtr)
                .idempotencyKey("FAIL-" + UUID.randomUUID())  // synthetic — does NOT block retry
                .senderVpa(req.getSenderVpa())
                .receiverVpa(req.getReceiverVpa())
                .amount(req.getAmount())
                .status(TxnStatus.FAILED)
                .channel(channel)
                .failureReason(reason)
                .build();
        txnRepo.save(failed);
        log.info("Audit logged FAIL: {} -> {} ₹{} reason={}",
                req.getSenderVpa(), req.getReceiverVpa(), req.getAmount(), reason);
    }
}
