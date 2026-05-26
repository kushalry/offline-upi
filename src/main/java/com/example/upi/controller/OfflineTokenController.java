package com.example.upi.controller;

import com.example.upi.dto.Dtos.*;
import com.example.upi.service.OfflineTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * P2P offline token endpoints.
 *
 * REAL-WORLD FLOW:
 *   1. Sender (online): POST /issue → gets OfflineTokenPayload
 *   2. Sender hands payload to receiver via Bluetooth/NFC/QR (off-band)
 *   3. Either party (when next online): POST /redeem with the payload
 *
 * The /verify endpoint is exposed for testing — in production the receiver's
 * device verifies locally using the cached sender public key.
 */
@RestController
@RequestMapping("/api/offline-tokens")
@RequiredArgsConstructor
@Tag(name = "P2P Offline Tokens", description = "Sign + verify + settle offline payments")
public class OfflineTokenController {

    private final OfflineTokenService tokenService;

    @PostMapping("/issue")
    @Operation(summary = "Issue a signed offline token. Reserves funds from sender's UPI Lite wallet.")
    public ResponseEntity<OfflineTokenPayload> issue(@Valid @RequestBody IssueTokenRequest req) {
        return ResponseEntity.ok(tokenService.issue(req));
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify a token's signature without settling it. Use when both parties offline.")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var token = (Map<String, Object>) body.get("token");
        String publicKey = (String) body.get("senderPublicKeyBase64");

        // Hand-roll the payload parse to keep the demo simple
        OfflineTokenPayload payload = OfflineTokenPayload.builder()
                .nonce((String) token.get("nonce"))
                .senderVpa((String) token.get("senderVpa"))
                .receiverVpa((String) token.get("receiverVpa"))
                .amount(new java.math.BigDecimal(token.get("amount").toString()))
                .issuedAt(java.time.Instant.parse((String) token.get("issuedAt")))
                .expiresAt(java.time.Instant.parse((String) token.get("expiresAt")))
                .signedPayload((String) token.get("signedPayload"))
                .signature((String) token.get("signature"))
                .build();

        boolean valid = tokenService.verifyOffline(payload, publicKey);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    @PostMapping("/redeem")
    @Operation(summary = "Settle a previously-issued offline token. Either party can call this.")
    public ResponseEntity<RedeemTokenResponse> redeem(@Valid @RequestBody RedeemTokenRequest req) {
        return ResponseEntity.ok(tokenService.redeem(req.getToken()));
    }
}
