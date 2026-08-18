package com.coderbank.coderbank_transaction_service.dto.request;

import com.coderbank.coderbank_transaction_service.model.AccountType;
import com.coderbank.coderbank_transaction_service.model.CurrencyType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AccountRequestDTO(
        @NotNull(message = "Customer ID é obrigatório")
        UUID customerId,

        @NotNull(message = "Tipo da conta é obrigatório")
        AccountType accountType,

        @NotNull(message = "A moeda é obrigatória")
        CurrencyType currency
) {
}
