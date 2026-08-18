package com.coderbank.coderbank_transaction_service.exceptions;

import java.time.OffsetDateTime;

public record ApiErrorResponse(
        String code,
        String message,
        OffsetDateTime timestamp
) {
}
