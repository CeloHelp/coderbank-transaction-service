package com.coderbank.coderbank_transaction_service.repository;

import com.coderbank.coderbank_transaction_service.model.AccountCreationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface AccountCreationRequestRepository extends JpaRepository<AccountCreationRequest, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO account_creation_requests (
                idempotency_key, customer_id, account_type, currency
            ) VALUES (
                :key, :customerId, :accountType, :currency
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int reserve(
            @Param("key") UUID key,
            @Param("customerId") UUID customerId,
            @Param("accountType") String accountType,
            @Param("currency") String currency
    );
}
