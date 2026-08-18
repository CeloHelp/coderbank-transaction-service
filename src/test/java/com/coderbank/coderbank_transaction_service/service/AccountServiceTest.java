package com.coderbank.coderbank_transaction_service.service;

import com.coderbank.coderbank_transaction_service.dto.request.AccountRequestDTO;
import com.coderbank.coderbank_transaction_service.dto.response.AccountCreationResult;
import com.coderbank.coderbank_transaction_service.exceptions.CheckingAccountRequiredException;
import com.coderbank.coderbank_transaction_service.exceptions.IdempotencyKeyConflictException;
import com.coderbank.coderbank_transaction_service.model.Account;
import com.coderbank.coderbank_transaction_service.model.AccountCreationRequest;
import com.coderbank.coderbank_transaction_service.model.AccountType;
import com.coderbank.coderbank_transaction_service.model.CurrencyType;
import com.coderbank.coderbank_transaction_service.repository.AccountCreationRequestRepository;
import com.coderbank.coderbank_transaction_service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountCreationRequestRepository creationRequestRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(accountRepository, creationRequestRepository, transactionTemplate);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        });
    }

    @Test
    void rejectsSameKeyWithDifferentPayload() {
        UUID key = UUID.randomUUID();
        AccountRequestDTO request = request(UUID.randomUUID(), AccountType.CHECKING);
        AccountCreationRequest reservation = mock(AccountCreationRequest.class);
        when(creationRequestRepository.reserve(any(), any(), any(), any())).thenReturn(0);
        when(creationRequestRepository.findById(key)).thenReturn(Optional.of(reservation));
        when(reservation.hasSamePayload(request.customerId(), request.accountType(), request.currency()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.createAccount(key, request))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void rejectsSavingsWhenCheckingDoesNotExist() {
        UUID key = UUID.randomUUID();
        AccountRequestDTO request = request(UUID.randomUUID(), AccountType.SAVINGS);
        when(creationRequestRepository.reserve(any(), any(), any(), any())).thenReturn(1);
        when(creationRequestRepository.findById(key)).thenReturn(Optional.of(mock(AccountCreationRequest.class)));
        when(accountRepository.existsByCustomerIdAndAccountType(request.customerId(), AccountType.SAVINGS))
                .thenReturn(false);
        when(accountRepository.existsByCustomerIdAndAccountType(request.customerId(), AccountType.CHECKING))
                .thenReturn(false);

        assertThatThrownBy(() -> service.createAccount(key, request))
                .isInstanceOf(CheckingAccountRequiredException.class);
    }

    @Test
    void returnsOriginalAccountForReplay() {
        UUID key = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRequestDTO request = request(UUID.randomUUID(), AccountType.CHECKING);
        AccountCreationRequest reservation = mock(AccountCreationRequest.class);
        Account account = mock(Account.class);
        when(creationRequestRepository.reserve(any(), any(), any(), any())).thenReturn(0);
        when(creationRequestRepository.findById(key)).thenReturn(Optional.of(reservation));
        when(reservation.hasSamePayload(request.customerId(), request.accountType(), request.currency()))
                .thenReturn(true);
        when(reservation.getAccountId()).thenReturn(accountId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(account.getId()).thenReturn(accountId);
        when(account.getCustomerId()).thenReturn(request.customerId());
        when(account.getAccountType()).thenReturn(request.accountType());
        when(account.getBalance()).thenReturn(BigDecimal.ZERO);
        when(account.getCurrency()).thenReturn(request.currency());
        when(account.getCreatedAt()).thenReturn(LocalDateTime.now());

        AccountCreationResult result = service.createAccount(key, request);

        assertThat(result.replayed()).isTrue();
        assertThat(result.account().accountId()).isEqualTo(accountId);
    }

    private AccountRequestDTO request(UUID customerId, AccountType accountType) {
        return new AccountRequestDTO(customerId, accountType, CurrencyType.BRL);
    }
}
