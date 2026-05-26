package com.example.upi.repository;

import com.example.upi.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByMobileNumber(String mobileNumber);
    Optional<Account> findByVpa(String vpa);
    boolean existsByVpa(String vpa);
    boolean existsByMobileNumber(String mobileNumber);

    /**
     * For batch settlement we want a strict lock — pessimistic write.
     * Used by the reconciliation engine when settling many tokens at once.
     * Optimistic locking is the default elsewhere; we pick pessimistic here
     * because we know we'll mutate and don't want to retry under contention.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.vpa = :vpa")
    Optional<Account> findByVpaForUpdate(@Param("vpa") String vpa);
}
