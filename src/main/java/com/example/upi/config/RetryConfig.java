package com.example.upi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry for the @Retryable annotation in TransactionService.
 * Used to retry on OptimisticLockingFailureException with exponential backoff.
 *
 * NOTE: spring-retry is brought in transitively by spring-boot-starter-data-jpa.
 * If it's missing at runtime, remove this class and the @Retryable on
 * TransactionService.transferWithRetry — the demo flows don't depend on it.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
