package com.example.upi.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

public class Dtos {

    // ---------- Auth ----------

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RegisterRequest {
        @NotBlank @Pattern(regexp = "\\d{10}", message = "Must be 10 digits") private String mobileNumber;
        @NotBlank private String accountHolderName;
        @NotBlank @Pattern(regexp = "[a-z0-9.]{3,30}@upi", message = "VPA must be like name@upi") private String vpa;
        @NotBlank private String bankAccountNumber;
        @NotBlank @Pattern(regexp = "[A-Z]{4}0[A-Z0-9]{6}") private String ifscCode;
        @NotBlank @Pattern(regexp = "\\d{4,6}") private String upiPin;
        @NotBlank @Size(min = 8) private String password;
        @NotNull @DecimalMin("0.00") private BigDecimal openingBalance;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LoginRequest {
        @NotBlank private String vpa;
        @NotBlank private String password;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LoginResponse {
        private String token;
        private String vpa;
        private long expiresInSec;
    }

    // ---------- Online & USSD transfer ----------

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TransferRequest {
        @NotBlank private String idempotencyKey;
        @NotBlank private String senderVpa;
        @NotBlank private String receiverVpa;
        @NotNull @DecimalMin("0.01") private BigDecimal amount;
        @NotBlank @Pattern(regexp = "\\d{4,6}") private String upiPin;
        private String remarks;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TransferResponse {
        private String utr;
        private String status;
        private String message;
        private BigDecimal newBalance;
        private boolean idempotentReplay; // true if this was a duplicate request returning cached result
    }

    // ---------- USSD gateway ----------

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UssdRequest {
        @NotBlank private String sessionId;
        @NotBlank @Pattern(regexp = "\\d{10}") private String mobileNumber;
        private String text;
        private String serviceCode;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UssdResponse {
        private String type;     // "CON" or "END"
        private String message;
    }

    // ---------- UPI Lite (deferred settlement) ----------

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LiteLoadRequest {
        @NotBlank private String idempotencyKey;
        @NotNull @DecimalMin("0.01") private BigDecimal amount;
        @NotBlank @Pattern(regexp = "\\d{4,6}") private String upiPin;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LiteSpendRequest {
        @NotBlank private String idempotencyKey;
        @NotBlank private String receiverVpa;
        @NotNull @DecimalMin("0.01") private BigDecimal amount;
        private String remarks;
        // No PIN required — this is the UPI Lite UX advantage. Authn is the device unlock.
    }

    // ---------- P2P Offline Tokens ----------

    /** Sender pre-issues a token to be handed off via Bluetooth/NFC/QR. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class IssueTokenRequest {
        @NotBlank private String senderVpa;
        @NotBlank private String receiverVpa;
        @NotNull @DecimalMin("0.01") private BigDecimal amount;
        @NotBlank @Pattern(regexp = "\\d{4,6}") private String upiPin;
    }

    /** The actual token payload — small enough to fit in an NFC/QR/BT exchange. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class OfflineTokenPayload {
        private String nonce;
        private String senderVpa;
        private String receiverVpa;
        private BigDecimal amount;
        private Instant issuedAt;
        private Instant expiresAt;
        private String signedPayload; // canonical string that was signed
        private String signature;     // base64 RSA signature
    }

    /** When either party reconnects, they redeem the token. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RedeemTokenRequest {
        private OfflineTokenPayload token;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RedeemTokenResponse {
        private String status;
        private String utr;
        private String message;
    }

    // ---------- Account snapshot ----------

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AccountSnapshot {
        private String vpa;
        private String accountHolderName;
        private BigDecimal balance;
        private BigDecimal upiLiteBalance;
        private BigDecimal upiLiteMaxBalance;
        private BigDecimal offlineLimitPerTxn;
    }
}
