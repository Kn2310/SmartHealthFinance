package com.smarthealthfinance.insights.presentation.handler;

import com.smarthealthfinance.insights.application.exception.InsightNotFoundException;
import com.smarthealthfinance.insights.application.exception.RelatedTransactionNotFoundException;
import com.smarthealthfinance.insights.domain.exception.InsightNotActiveException;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.error.ApiErrors;
import com.smarthealthfinance.shared.presentation.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Códigos estáveis da API de Insights (ADR-0023 §1). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InsightsExceptionHandler {

    @ExceptionHandler(InsightNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return respond(ErrorCode.INSIGHT_NOT_FOUND);
    }

    @ExceptionHandler(InsightNotActiveException.class)
    ResponseEntity<ApiError> notActive() {
        return respond(ErrorCode.INSIGHT_NOT_ACTIVE);
    }

    @ExceptionHandler(RelatedTransactionNotFoundException.class)
    ResponseEntity<ApiError> transactionNotFound() {
        return respond(ErrorCode.TRANSACTION_NOT_FOUND);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
