package com.example.upi.service;

import com.example.upi.dto.Dtos.*;
import com.example.upi.exception.Exceptions.*;
import com.example.upi.model.Account;
import com.example.upi.repository.AccountRepository;
import com.example.upi.security.JwtService;
import com.example.upi.util.Hashing;
import com.example.upi.util.RsaCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepo;
    private final JwtService jwt;

    /**
     * In-memory store of issued private keys for demo purposes.
     * In production this NEVER exists server-side — keys live in Android Keystore /
     * iOS Secure Enclave on the user's device.
     */
    private static final Map<String, String> demoPrivateKeys = new java.util.concurrent.ConcurrentHashMap<>();

    @Transactional
    public Account register(RegisterRequest req) {
        if (accountRepo.existsByMobileNumber(req.getMobileNumber()))
            throw new DuplicateAccountException("Mobile already registered");
        if (accountRepo.existsByVpa(req.getVpa()))
            throw new DuplicateAccountException("VPA already taken");

        // Generate RSA keypair — public key persisted, private key returned to "device"
        KeyPair kp = RsaCrypto.generateKeyPair();
        String publicKeyB64 = RsaCrypto.encodePublicKey(kp.getPublic());
        String privateKeyB64 = RsaCrypto.encodePrivateKey(kp.getPrivate());

        Account acc = Account.builder()
                .mobileNumber(req.getMobileNumber())
                .vpa(req.getVpa())
                .accountHolderName(req.getAccountHolderName())
                .bankAccountNumber(req.getBankAccountNumber())
                .ifscCode(req.getIfscCode())
                .pinHash(Hashing.hash(req.getUpiPin()))
                .passwordHash(Hashing.hash(req.getPassword()))
                .balance(req.getOpeningBalance())
                .publicKeyBase64(publicKeyB64)
                .build();
        Account saved = accountRepo.save(acc);

        // Demo only — see field comment above
        demoPrivateKeys.put(saved.getVpa(), privateKeyB64);

        log.info("Registered account vpa={} mobile={}", saved.getVpa(), saved.getMobileNumber());
        return saved;
    }

    /** Demo helper — fetches the "device" private key. Not exposed via REST. */
    public String getDevicePrivateKey(String vpa) {
        String pk = demoPrivateKeys.get(vpa);
        if (pk == null) throw new IllegalStateException("No device key for " + vpa);
        return pk;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        Account acc = accountRepo.findByVpa(req.getVpa())
                .orElseThrow(() -> new AccountNotFoundException("VPA not found"));
        if (!Hashing.matches(req.getPassword(), acc.getPasswordHash())) {
            throw new InvalidPinException(); // intentionally vague — don't leak whether VPA exists
        }
        return LoginResponse.builder()
                .token(jwt.issue(acc.getVpa()))
                .vpa(acc.getVpa())
                .expiresInSec(jwt.getTtlSeconds())
                .build();
    }

    @Transactional(readOnly = true)
    public Account getByVpa(String vpa) {
        return accountRepo.findByVpa(vpa)
                .orElseThrow(() -> new AccountNotFoundException("VPA not found: " + vpa));
    }

    @Transactional(readOnly = true)
    public Account getByMobile(String mobile) {
        return accountRepo.findByMobileNumber(mobile)
                .orElseThrow(() -> new AccountNotFoundException("Mobile not registered: " + mobile));
    }

    public AccountSnapshot toSnapshot(Account acc) {
        return AccountSnapshot.builder()
                .vpa(acc.getVpa())
                .accountHolderName(acc.getAccountHolderName())
                .balance(acc.getBalance())
                .upiLiteBalance(acc.getUpiLiteBalance())
                .upiLiteMaxBalance(acc.getUpiLiteMaxBalance())
                .offlineLimitPerTxn(acc.getOfflineLimitPerTxn())
                .build();
    }
}
