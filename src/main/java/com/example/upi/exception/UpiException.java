package com.example.upi.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception for domain-level failures. Every subclass maps to a specific
 * HTTP status code, which the GlobalExceptionHandler reads. This keeps controllers
 * clean — no try/catch boilerplate.
 */
public abstract class UpiException extends RuntimeException {
    public UpiException(String msg) { super(msg); }
    public abstract HttpStatus getStatus();
    public abstract String getErrorCode();
}
