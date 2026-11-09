package com.smarthealthfinance.consents.presentation.handler;

import com.smarthealthfinance.consents.application.exception.ConsentPurposeNotFoundException;
import com.smarthealthfinance.consents.domain.exception.ConsentPrerequisiteMissingException;
import com.smarthealthfinance.consents.domain.exception.ConsentTermsOutdatedException;
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
public class ConsentsExceptionHandler {

    @ExceptionHandler(ConsentPurposeNotFoundException.class)
    ResponseEntity<ApiError> purposeNotFound() {
        return respond(ErrorCode.CONSENT_PURPOSE_NOT_FOUND);
    }

    @ExceptionHandler(ConsentTermsOutdatedException.class)
    ResponseEntity<ApiError> termsOutdated() {
        return respond(ErrorCode.CONSENT_TERMS_OUTDATED);
    }

    @ExceptionHandler(ConsentPrerequisiteMissingException.class)
    ResponseEntity<ApiError> prerequisiteMissing() {
        return respond(ErrorCode.CONSENT_PREREQUISITE_MISSING);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
