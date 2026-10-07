package com.smarthealthfinance.transactions.presentation.handler;

import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.error.ApiErrors;
import com.smarthealthfinance.shared.presentation.error.ErrorCode;
import com.smarthealthfinance.transactions.application.exception.IdempotencyKeyReusedException;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionsExceptionHandler {

    @ExceptionHandler(TransactionNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return respond(ErrorCode.TRANSACTION_NOT_FOUND);
    }

    @ExceptionHandler(InvalidTransactionStatusTransitionException.class)
    ResponseEntity<ApiError> invalidTransition() {
        return respond(ErrorCode.TRANSACTION_STATUS_CONFLICT);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ApiError> idempotencyKeyReused() {
        return respond(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
