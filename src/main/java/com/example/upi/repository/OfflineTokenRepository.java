package com.example.upi.repository;

import com.example.upi.model.OfflineToken;
import com.example.upi.model.OfflineToken.TokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OfflineTokenRepository extends JpaRepository<OfflineToken, Long> {

    /** The double-spend defense: nonce uniqueness is enforced at DB level too. */
    Optional<OfflineToken> findByNonce(String nonce);

    /** Reconciliation engine: find tokens to settle. */
    List<OfflineToken> findByStatusOrderByIssuedAtAsc(TokenStatus status);

    /** Background sweeper: expire stale unsettled tokens. */
    List<OfflineToken> findByStatusInAndExpiresAtBefore(List<TokenStatus> statuses, Instant cutoff);
}
