package com.example.upi.controller;

import com.example.upi.dto.Dtos.UssdRequest;
import com.example.upi.dto.Dtos.UssdResponse;
import com.example.upi.service.UssdService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ussd")
@RequiredArgsConstructor
@Tag(name = "USSD Gateway", description = "Simulated *99# endpoint — called by telecom carrier in production")
public class UssdController {

    private final UssdService ussdService;

    @PostMapping
    @Operation(summary = "Receives one keypress at a time from the carrier; returns CON (more) or END")
    public ResponseEntity<UssdResponse> handle(@Valid @RequestBody UssdRequest req) {
        return ResponseEntity.ok(ussdService.handle(req));
    }
}
