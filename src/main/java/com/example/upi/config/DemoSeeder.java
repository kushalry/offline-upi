package com.example.upi.config;

import com.example.upi.dto.Dtos.RegisterRequest;
import com.example.upi.repository.AccountRepository;
import com.example.upi.service.AccountService;
import com.example.upi.service.TransactionService;
import com.example.upi.dto.Dtos.LiteLoadRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Seeds two demo accounts so the README's curl commands work immediately.
 * Loads ₹1000 into each account's UPI Lite wallet so offline flows are testable
 * without needing to load Lite first.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DemoSeeder implements CommandLineRunner {

    private final AccountService accountService;
    private final TransactionService txnService;
    private final AccountRepository accountRepo;

    @Override
    public void run(String... args) {
        if (accountRepo.count() > 0) {
            log.info("DB already seeded, skipping");
            return;
        }
        log.info("Seeding demo accounts...");

        accountService.register(RegisterRequest.builder()
                .mobileNumber("9876543210").accountHolderName("Ramesh Kumar")
                .vpa("ramesh@upi").bankAccountNumber("12345678901").ifscCode("HDFC0001234")
                .upiPin("1234").password("password123").openingBalance(new BigDecimal("5000"))
                .build());

        accountService.register(RegisterRequest.builder()
                .mobileNumber("9123456780").accountHolderName("Sita Devi")
                .vpa("sita@upi").bankAccountNumber("98765432101").ifscCode("SBIN0005678")
                .upiPin("5678").password("password456").openingBalance(new BigDecimal("3000"))
                .build());

        // Pre-load both with UPI Lite balance so they can transact offline immediately
        txnService.loadUpiLite("ramesh@upi", LiteLoadRequest.builder()
                .idempotencyKey("seed-" + UUID.randomUUID())
                .amount(new BigDecimal("1000")).upiPin("1234").build());

        txnService.loadUpiLite("sita@upi", LiteLoadRequest.builder()
                .idempotencyKey("seed-" + UUID.randomUUID())
                .amount(new BigDecimal("1000")).upiPin("5678").build());

        log.info("Seeded: ramesh@upi (PIN 1234, pwd password123), sita@upi (PIN 5678, pwd password456)");
        log.info("Both accounts have ₹4000 bank + ₹1000 UPI Lite");
    }
}
