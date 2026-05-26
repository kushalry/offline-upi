package com.example.upi;

import com.example.upi.dto.Dtos.*;
import com.example.upi.service.AccountService;
import com.example.upi.service.OfflineTokenService;
import com.example.upi.service.TransactionService;
import com.example.upi.model.Transaction.TxnChannel;
import com.example.upi.exception.Exceptions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext
class OfflineUpiIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired TransactionService txnService;
    @Autowired OfflineTokenService tokenService;

    @Test
    void idempotency_replayReturnsCachedResult() {
        String key = "idem-" + UUID.randomUUID();
        TransferRequest req = TransferRequest.builder()
                .idempotencyKey(key)
                .senderVpa("ramesh@upi").receiverVpa("sita@upi")
                .amount(new BigDecimal("10")).upiPin("1234")
                .build();

        TransferResponse first = txnService.transfer(req, TxnChannel.ONLINE);
        TransferResponse second = txnService.transfer(req, TxnChannel.ONLINE);

        assertEquals(first.getUtr(), second.getUtr(), "Same UTR must be returned on idempotent replay");
        assertTrue(second.isIdempotentReplay(), "Second call must be flagged as replay");
    }

    @Test
    void offlineTransfer_overLimitRejected() {
        TransferRequest req = TransferRequest.builder()
                .idempotencyKey("idem-" + UUID.randomUUID())
                .senderVpa("ramesh@upi").receiverVpa("sita@upi")
                .amount(new BigDecimal("501"))   // exceeds ₹500 offline limit
                .upiPin("1234").build();

        assertThrows(LimitExceededException.class,
                () -> txnService.transfer(req, TxnChannel.USSD_OFFLINE));
    }

    @Test
    void invalidPin_rejected() {
        TransferRequest req = TransferRequest.builder()
                .idempotencyKey("idem-" + UUID.randomUUID())
                .senderVpa("ramesh@upi").receiverVpa("sita@upi")
                .amount(new BigDecimal("50")).upiPin("9999") // wrong
                .build();

        assertThrows(InvalidPinException.class,
                () -> txnService.transfer(req, TxnChannel.ONLINE));
    }

    @Test
    void offlineToken_issueVerifyRedeem_endToEnd() {
        // Issue
        IssueTokenRequest issue = IssueTokenRequest.builder()
                .senderVpa("ramesh@upi").receiverVpa("sita@upi")
                .amount(new BigDecimal("100")).upiPin("1234").build();
        OfflineTokenPayload token = tokenService.issue(issue);

        assertNotNull(token.getNonce());
        assertNotNull(token.getSignature());

        // Verify with sender's public key (what the offline receiver does)
        var sender = accountService.getByVpa("ramesh@upi");
        boolean valid = tokenService.verifyOffline(token, sender.getPublicKeyBase64());
        assertTrue(valid, "Token must verify with sender's public key");

        // Redeem
        RedeemTokenResponse resp = tokenService.redeem(token);
        assertEquals("SETTLED", resp.getStatus());
    }

    @Test
    void offlineToken_doubleRedeemReturnsCached() {
        IssueTokenRequest issue = IssueTokenRequest.builder()
                .senderVpa("ramesh@upi").receiverVpa("sita@upi")
                .amount(new BigDecimal("25")).upiPin("1234").build();
        OfflineTokenPayload token = tokenService.issue(issue);

        RedeemTokenResponse first = tokenService.redeem(token);
        RedeemTokenResponse second = tokenService.redeem(token);

        assertEquals("SETTLED", first.getStatus());
        assertEquals("ALREADY_SETTLED", second.getStatus());
        assertEquals(first.getUtr(), second.getUtr());
    }

    @Test
    void offlineToken_tamperedPayloadRejected() {
        IssueTokenRequest issue = IssueTokenRequest.builder()
                .senderVpa("ramesh@upi").receiverVpa("sita@upi")
                .amount(new BigDecimal("50")).upiPin("1234").build();
        OfflineTokenPayload token = tokenService.issue(issue);

        // Tamper: change amount but keep signature
        token.setAmount(new BigDecimal("5000"));

        assertThrows(TokenVerificationException.class,
                () -> tokenService.redeem(token));
    }
}
