package com.example.upi.exception;

import org.springframework.http.HttpStatus;

public class Exceptions {

    public static class AccountNotFoundException extends UpiException {
        public AccountNotFoundException(String msg) { super(msg); }
        @Override public HttpStatus getStatus() { return HttpStatus.NOT_FOUND; }
        @Override public String getErrorCode() { return "ACCOUNT_NOT_FOUND"; }
    }

    public static class DuplicateAccountException extends UpiException {
        public DuplicateAccountException(String msg) { super(msg); }
        @Override public HttpStatus getStatus() { return HttpStatus.CONFLICT; }
        @Override public String getErrorCode() { return "DUPLICATE_ACCOUNT"; }
    }

    public static class InvalidPinException extends UpiException {
        public InvalidPinException() { super("Invalid UPI PIN"); }
        @Override public HttpStatus getStatus() { return HttpStatus.UNAUTHORIZED; }
        @Override public String getErrorCode() { return "INVALID_PIN"; }
    }

    public static class InsufficientBalanceException extends UpiException {
        public InsufficientBalanceException(String msg) { super(msg); }
        @Override public HttpStatus getStatus() { return HttpStatus.UNPROCESSABLE_ENTITY; }
        @Override public String getErrorCode() { return "INSUFFICIENT_BALANCE"; }
    }

    public static class LimitExceededException extends UpiException {
        public LimitExceededException(String msg) { super(msg); }
        @Override public HttpStatus getStatus() { return HttpStatus.UNPROCESSABLE_ENTITY; }
        @Override public String getErrorCode() { return "LIMIT_EXCEEDED"; }
    }

    public static class TokenVerificationException extends UpiException {
        public TokenVerificationException(String msg) { super(msg); }
        @Override public HttpStatus getStatus() { return HttpStatus.UNPROCESSABLE_ENTITY; }
        @Override public String getErrorCode() { return "TOKEN_INVALID"; }
    }

    public static class DoubleSpendException extends UpiException {
        public DoubleSpendException(String msg) { super(msg); }
        @Override public HttpStatus getStatus() { return HttpStatus.CONFLICT; }
        @Override public String getErrorCode() { return "DOUBLE_SPEND"; }
    }
}
