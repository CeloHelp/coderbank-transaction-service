package com.coderbank.coderbank_transaction_service.service;

import com.coderbank.coderbank_transaction_service.dto.request.AccountRequestDTO;
import com.coderbank.coderbank_transaction_service.dto.response.AccountCreationResult;
import com.coderbank.coderbank_transaction_service.dto.response.AccountResponseDTO;
import com.coderbank.coderbank_transaction_service.exceptions.AccountTypeAlreadyExistsException;
import com.coderbank.coderbank_transaction_service.exceptions.CheckingAccountRequiredException;
import com.coderbank.coderbank_transaction_service.exceptions.IdempotencyKeyConflictException;
import com.coderbank.coderbank_transaction_service.exceptions.IncompleteIdempotencyRequestException;
import com.coderbank.coderbank_transaction_service.model.Account;
import com.coderbank.coderbank_transaction_service.model.AccountCreationRequest;
import com.coderbank.coderbank_transaction_service.model.AccountType;
import com.coderbank.coderbank_transaction_service.repository.AccountCreationRequestRepository;
import com.coderbank.coderbank_transaction_service.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final AccountCreationRequestRepository creationRequestRepository;
    private final TransactionTemplate transactionTemplate;

    public AccountCreationResult createAccount(UUID idempotencyKey, AccountRequestDTO request) {
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> createInTransaction(idempotencyKey, request)));
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uq_accounts_customer_type")) {
                throw new AccountTypeAlreadyExistsException();
            }
            throw exception;
        }
    }

    private AccountCreationResult createInTransaction(UUID idempotencyKey, AccountRequestDTO request) {
        int reserved = creationRequestRepository.reserve(
                idempotencyKey,
                request.customerId(),
                request.accountType().name(),
                request.currency().name()
        );

        AccountCreationRequest creationRequest = creationRequestRepository.findById(idempotencyKey)
                .orElseThrow(IncompleteIdempotencyRequestException::new);

        if (reserved == 0) {
            if (!creationRequest.hasSamePayload(request.customerId(), request.accountType(), request.currency())) {
                throw new IdempotencyKeyConflictException();
            }
            if (creationRequest.getAccountId() == null) {
                throw new IncompleteIdempotencyRequestException();
            }

            Account account = accountRepository.findById(creationRequest.getAccountId())
                    .orElseThrow(IncompleteIdempotencyRequestException::new);
            return new AccountCreationResult(toResponse(account), true);
        }

        if (accountRepository.existsByCustomerIdAndAccountType(request.customerId(), request.accountType())) {
            throw new AccountTypeAlreadyExistsException();
        }

        if (request.accountType() == AccountType.SAVINGS
                && !accountRepository.existsByCustomerIdAndAccountType(request.customerId(), AccountType.CHECKING)) {
            throw new CheckingAccountRequiredException();
        }

        Account account = Account.open(
                request.customerId(),
                request.accountType(),
                request.currency()
        );

        accountRepository.saveAndFlush(account);
        creationRequest.complete(account.getId());
        creationRequestRepository.saveAndFlush(creationRequest);

        return new AccountCreationResult(toResponse(account), false);
    }

    private AccountResponseDTO toResponse(Account account) {
        return new AccountResponseDTO(account.getId(),
                account.getCustomerId(),
                account.getAccountType(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt());
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
