package com.example.upi.controller;

import com.example.upi.dto.Dtos.AccountSnapshot;
import com.example.upi.service.AccountService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/me")
    public ResponseEntity<AccountSnapshot> me(@AuthenticationPrincipal String vpa) {
        return ResponseEntity.ok(accountService.toSnapshot(accountService.getByVpa(vpa)));
    }

    @GetMapping("/{vpa}")
    public ResponseEntity<AccountSnapshot> getByVpa(@PathVariable String vpa) {
        return ResponseEntity.ok(accountService.toSnapshot(accountService.getByVpa(vpa)));
    }
}
