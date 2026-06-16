package com.coderbank.coderbank_transaction_service.dto.response;

import com.coderbank.coderbank_transaction_service.model.CurrencyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AccountResponseDTO (
        UUID accountId,

        String customerId,

        BigDecimal balance,


        LocalDateTime createdAt
){
}
