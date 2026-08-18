package com.coderbank.coderbank_transaction_service.model;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Getter
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    @Getter
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    @Getter
    private AccountType accountType;

    @Column(nullable = false, precision = 19, scale = 2)
    @Getter
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Getter
    private CurrencyType currency;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    @Getter
    private LocalDateTime createdAt;

    protected Account() {
    }

    private Account(UUID customerId, AccountType accountType, BigDecimal balance, CurrencyType currency) {
        this.customerId = customerId;
        this.accountType = accountType;
        this.balance = balance;
        this.currency = currency;
    }

    public static Account open(UUID customerId, AccountType accountType, CurrencyType currency) {
        return new Account(
                customerId,
                accountType,
                BigDecimal.ZERO,
                currency
        );
    }
}
