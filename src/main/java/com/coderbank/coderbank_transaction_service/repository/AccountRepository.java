package com.coderbank.coderbank_transaction_service.repository;

import com.coderbank.coderbank_transaction_service.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {


}
