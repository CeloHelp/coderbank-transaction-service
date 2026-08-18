package com.coderbank.coderbank_transaction_service.exceptions;

public class AccountTypeAlreadyExistsException extends RuntimeException {

    public AccountTypeAlreadyExistsException() {
        super("Cliente já possui uma conta deste tipo");
    }
}
