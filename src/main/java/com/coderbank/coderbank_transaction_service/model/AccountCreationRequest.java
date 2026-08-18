package com.coderbank.coderbank_transaction_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_creation_requests")
@Getter
public class AccountCreationRequest {

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CurrencyType currency;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AccountCreationRequest() {
    }

    public boolean hasSamePayload(UUID customerId, AccountType accountType, CurrencyType currency) {
        return this.customerId.equals(customerId)
                && this.accountType == accountType
                && this.currency == currency;
    }

    public void complete(UUID accountId) {
        this.accountId = accountId;
    }
}
