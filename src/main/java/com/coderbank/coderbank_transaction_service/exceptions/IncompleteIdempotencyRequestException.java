package com.coderbank.coderbank_transaction_service.exceptions;

public class IncompleteIdempotencyRequestException extends RuntimeException {

    public IncompleteIdempotencyRequestException() {
        super("Registro idempotente incompleto");
    }
}
