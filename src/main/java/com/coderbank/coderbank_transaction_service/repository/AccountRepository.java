package com.coderbank.coderbank_transaction_service.repository;

import com.coderbank.coderbank_transaction_service.model.Account;
import com.coderbank.coderbank_transaction_service.model.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByCustomerIdAndAccountType(UUID customerId, AccountType accountType);

    boolean existsByCustomerIdAndAccountType(UUID customerId, AccountType accountType);
}
