package com.coderbank.coderbank_transaction_service.service;

import com.coderbank.coderbank_transaction_service.dto.request.AccountRequestDTO;
import com.coderbank.coderbank_transaction_service.dto.response.AccountResponseDTO;
import com.coderbank.coderbank_transaction_service.model.Account;
import com.coderbank.coderbank_transaction_service.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class AccountService {
    private final AccountRepository accountRepository;

    public AccountResponseDTO  createAccount( AccountRequestDTO accountRequestDTO) {

        Account account = Account.createAccount(
                accountRequestDTO.customerId(),
                accountRequestDTO.amount(),
                accountRequestDTO.currency()
        );

        accountRepository.save(account);

        return new AccountResponseDTO(account.getId(),
                 account.getCustomerId(),
                 account.getBalance(),
                 account.getCreatedAt());


    }
}
