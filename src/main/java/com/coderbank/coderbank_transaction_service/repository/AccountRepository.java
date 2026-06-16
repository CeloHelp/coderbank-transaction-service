package com.coderbank.coderbank_transaction_service.repository;

import com.coderbank.coderbank_transaction_service.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {


}
