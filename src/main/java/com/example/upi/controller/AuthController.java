package com.example.upi.controller;

import com.example.upi.dto.Dtos.*;
import com.example.upi.model.Account;
import com.example.upi.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration and login")
public class AuthController {

    private final AccountService accountService;

    @PostMapping("/register")
    @Operation(summary = "Register a new UPI account. Generates RSA keypair for offline tokens.")
    public ResponseEntity<AccountSnapshot> register(@Valid @RequestBody RegisterRequest req) {
        Account acc = accountService.register(req);
        return ResponseEntity.ok(accountService.toSnapshot(acc));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with VPA + password. Returns JWT.")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(accountService.login(req));
    }
}
