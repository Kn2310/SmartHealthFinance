package com.smarthealthfinance.ingestion.presentation.handler;

import com.smarthealthfinance.ingestion.application.exception.ImportNotFoundException;
import com.smarthealthfinance.ingestion.domain.exception.InvalidImportStatusTransitionException;
import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.error.ApiErrors;
import com.smarthealthfinance.shared.presentation.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngestionExceptionHandler {

    @ExceptionHandler(ImportNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return respond(ErrorCode.IMPORT_NOT_FOUND);
    }

    @ExceptionHandler(InvalidImportStatusTransitionException.class)
    ResponseEntity<ApiError> invalidTransition() {
        return respond(ErrorCode.IMPORT_STATUS_CONFLICT);
    }

    /** O código do motivo é estável (ex.: {@code MISSING_COLUMN}) e nunca carrega conteúdo do arquivo. */
    @ExceptionHandler(StatementRejectedException.class)
    ResponseEntity<ApiError> rejected(StatementRejectedException ex) {
        ErrorCode code = ErrorCode.IMPORT_FILE_REJECTED;
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code, code.defaultMessage(),
                List.of(new ApiError.Detail("file", ex.reason(), "Arquivo recusado."))));
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
