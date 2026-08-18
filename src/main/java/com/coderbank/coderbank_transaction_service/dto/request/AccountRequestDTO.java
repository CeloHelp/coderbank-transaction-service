package com.coderbank.coderbank_transaction_service.dto.request;

import com.coderbank.coderbank_transaction_service.model.CurrencyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record AccountRequestDTO(
        @NotBlank(message = "Customer ID é obrigatório")
        String customerId,

        @NotNull(message = "Valor Inicial é obrigatório")
        @PositiveOrZero(message = "Valor Inicial deve ser positivo ou zero")
        BigDecimal amount,

        @NotNull(message = "A moeda é  obrigatório")
        CurrencyType currency,
        
        @NotNull(message = "Descrição é obrigatória")
        String description // ignoramos aqui?


) {
}
