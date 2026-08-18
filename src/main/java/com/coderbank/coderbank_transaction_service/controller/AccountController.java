package com.coderbank.coderbank_transaction_service.controller;

import com.coderbank.coderbank_transaction_service.dto.request.AccountRequestDTO;
import com.coderbank.coderbank_transaction_service.dto.response.AccountCreationResult;
import com.coderbank.coderbank_transaction_service.dto.response.AccountResponseDTO;
import com.coderbank.coderbank_transaction_service.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;


@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {
     private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponseDTO> createAccount(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody AccountRequestDTO accountRequestDTO
    ) {
        AccountCreationResult result = accountService.createAccount(idempotencyKey, accountRequestDTO);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.account());
    }
}
