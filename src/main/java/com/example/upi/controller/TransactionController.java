package com.example.upi.controller;

import com.example.upi.dto.Dtos.*;
import com.example.upi.model.Transaction.TxnChannel;
import com.example.upi.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Online UPI + UPI Lite operations")
public class TransactionController {

    private final TransactionService txnService;

    @PostMapping("/transfer")
    @Operation(summary = "Online UPI transfer (debits bank balance)")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest req) {
        return ResponseEntity.ok(txnService.transfer(req, TxnChannel.ONLINE));
    }

    @PostMapping("/lite/load")
    @Operation(summary = "Load funds from bank balance into UPI Lite wallet")
    public ResponseEntity<TransferResponse> liteLoad(@AuthenticationPrincipal String vpa,
                                                     @Valid @RequestBody LiteLoadRequest req) {
        return ResponseEntity.ok(txnService.loadUpiLite(vpa, req));
    }

    @PostMapping("/lite/spend")
    @Operation(summary = "Offline-style UPI Lite spend — no PIN required, debits Lite wallet")
    public ResponseEntity<TransferResponse> liteSpend(@AuthenticationPrincipal String vpa,
                                                      @Valid @RequestBody LiteSpendRequest req) {
        TransferRequest tr = TransferRequest.builder()
                .idempotencyKey(req.getIdempotencyKey())
                .senderVpa(vpa)
                .receiverVpa(req.getReceiverVpa())
                .amount(req.getAmount())
                .upiPin("0000") // unused for Lite — service skips PIN check on UPI_LITE channel
                .remarks(req.getRemarks())
                .build();
        return ResponseEntity.ok(txnService.transfer(tr, TxnChannel.UPI_LITE));
    }
}
