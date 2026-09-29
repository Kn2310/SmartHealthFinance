package com.smarthealthfinance.identity.presentation.handler;

import com.smarthealthfinance.identity.application.exception.IncompleteIdentityClaimsException;
import com.smarthealthfinance.identity.application.exception.UserNotProvisionedException;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.exception.UserDisabledException;
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
public class IdentityExceptionHandler {

    @ExceptionHandler(UserNotProvisionedException.class)
    ResponseEntity<ApiError> notProvisioned() {
        return respond(ErrorCode.USER_NOT_PROVISIONED);
    }

    @ExceptionHandler(UserDisabledException.class)
    ResponseEntity<ApiError> disabled() {
        return respond(ErrorCode.USER_DISABLED);
    }

    @ExceptionHandler(IncompleteIdentityClaimsException.class)
    ResponseEntity<ApiError> incompleteClaims() {
        return respond(ErrorCode.IDENTITY_CLAIMS_INCOMPLETE);
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    ResponseEntity<ApiError> workspaceNotFound() {
        return respond(ErrorCode.WORKSPACE_NOT_FOUND);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
