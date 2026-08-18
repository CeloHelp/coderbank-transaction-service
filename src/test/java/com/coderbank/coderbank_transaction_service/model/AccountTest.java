package com.coderbank.coderbank_transaction_service.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    @Test
    void opensAccountWithZeroBalance() {
        UUID customerId = UUID.randomUUID();

        Account account = Account.open(customerId, AccountType.CHECKING, CurrencyType.BRL);

        assertThat(account.getCustomerId()).isEqualTo(customerId);
        assertThat(account.getAccountType()).isEqualTo(AccountType.CHECKING);
        assertThat(account.getCurrency()).isEqualTo(CurrencyType.BRL);
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
