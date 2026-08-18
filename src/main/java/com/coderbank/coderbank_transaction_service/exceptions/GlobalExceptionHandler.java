package com.coderbank.coderbank_transaction_service.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyKeyConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    @ExceptionHandler(AccountTypeAlreadyExistsException.class)
    ResponseEntity<ApiErrorResponse> handleAccountTypeConflict(AccountTypeAlreadyExistsException exception) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_TYPE_ALREADY_EXISTS", exception.getMessage());
    }

    @ExceptionHandler(CheckingAccountRequiredException.class)
    ResponseEntity<ApiErrorResponse> handleCheckingRequired(CheckingAccountRequiredException exception) {
        return error(HttpStatus.CONFLICT, "CHECKING_ACCOUNT_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiErrorResponse> handleValidation(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Requisição inválida");
    }

    @ExceptionHandler(IncompleteIdempotencyRequestException.class)
    ResponseEntity<ApiErrorResponse> handleIncompleteRequest(IncompleteIdempotencyRequestException exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Não foi possível processar a requisição");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, OffsetDateTime.now()));
    }
}
