package com.coderbank.coderbank_transaction_service.exceptions;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("Idempotency-Key já utilizada com outro payload");
    }
}
