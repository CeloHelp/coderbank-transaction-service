package com.coderbank.coderbank_transaction_service.exceptions;

public class CheckingAccountRequiredException extends RuntimeException {

    public CheckingAccountRequiredException() {
        super("Uma conta corrente é necessária antes da abertura da poupança");
    }
}
