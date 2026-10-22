package com.smarthealthfinance.cards.presentation.handler;

import com.smarthealthfinance.cards.application.exception.CardNotFoundException;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.error.ApiErrors;
import com.smarthealthfinance.shared.presentation.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CardsExceptionHandler {

    @ExceptionHandler(CardNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return respond(ErrorCode.CARD_NOT_FOUND);
    }

    @ExceptionHandler(CreditCardArchivedException.class)
    ResponseEntity<ApiError> archived() {
        return respond(ErrorCode.CARD_ARCHIVED);
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    ResponseEntity<ApiError> invoiceNotFound() {
        return respond(ErrorCode.INVOICE_NOT_FOUND);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
