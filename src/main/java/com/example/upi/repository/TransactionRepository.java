package com.example.upi.repository;

import com.example.upi.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByUtr(String utr);

    /** Idempotency check — if this returns a row, return it instead of processing again. */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Page<Transaction> findBySenderVpaOrderByCreatedAtDesc(String senderVpa, Pageable pageable);
    Page<Transaction> findByReceiverVpaOrderByCreatedAtDesc(String receiverVpa, Pageable pageable);
}
