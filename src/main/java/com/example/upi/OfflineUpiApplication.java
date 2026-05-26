package com.example.upi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(info = @Info(
        title = "Offline UPI API",
        version = "1.0",
        description = "Production-grade offline UPI demonstrating USSD, UPI Lite (deferred settlement), " +
                      "and P2P offline tokens (signed, verifiable without backend)."
))
public class OfflineUpiApplication {
    public static void main(String[] args) {
        SpringApplication.run(OfflineUpiApplication.class, args);
    }
}
