package com.smarthealthfinance.forecast.presentation.handler;

import com.smarthealthfinance.forecast.application.exception.ForecastCardNotFoundException;
import com.smarthealthfinance.forecast.application.exception.RecurringSeriesNotFoundException;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.error.ApiErrors;
import com.smarthealthfinance.shared.presentation.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Códigos estáveis da API de Forecast (ADR-0032). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ForecastExceptionHandler {

    @ExceptionHandler(RecurringSeriesNotFoundException.class)
    ResponseEntity<ApiError> seriesNotFound() {
        return respond(ErrorCode.RECURRING_SERIES_NOT_FOUND);
    }

    @ExceptionHandler(ForecastCardNotFoundException.class)
    ResponseEntity<ApiError> cardNotFound() {
        return respond(ErrorCode.CARD_NOT_FOUND);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
