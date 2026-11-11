package com.smarthealthfinance.copilot.presentation.handler;

import com.smarthealthfinance.copilot.application.exception.ContextNotFoundException;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.exception.CopilotAlreadyAnsweredException;
import com.smarthealthfinance.copilot.application.exception.CopilotConsentRequiredException;
import com.smarthealthfinance.copilot.application.exception.CopilotKeyReusedException;
import com.smarthealthfinance.copilot.application.exception.CopilotQuotaExceededException;
import com.smarthealthfinance.copilot.application.exception.CopilotRateLimitedException;
import com.smarthealthfinance.copilot.application.exception.CopilotRequestInProgressException;
import com.smarthealthfinance.copilot.application.exception.CopilotRequestNotFoundException;
import com.smarthealthfinance.copilot.application.exception.CopilotUnavailableException;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.error.ApiErrors;
import com.smarthealthfinance.shared.presentation.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Erros da API do Copilot (ADR-0040 §4) no contrato {@link ApiError}; nunca expõe pergunta, resposta ou valor. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CopilotExceptionHandler {

    /** Corpo do 429 de quota: o contrato de erro com {@code resetsAt}, quando a quota volta. */
    public record QuotaExceededError(String code, String message, List<ApiError.Detail> details, String traceId,
                                     Instant resetsAt) {
    }

    @ExceptionHandler(CopilotConsentRequiredException.class)
    ResponseEntity<ApiError> consentRequired() {
        return respond(ErrorCode.COPILOT_CONSENT_REQUIRED);
    }

    @ExceptionHandler(CopilotRateLimitedException.class)
    ResponseEntity<ApiError> rateLimited(CopilotRateLimitedException e) {
        ErrorCode code = ErrorCode.COPILOT_RATE_LIMITED;
        return ResponseEntity.status(code.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds(e.retryAfter())))
                .body(ApiErrors.of(code));
    }

    @ExceptionHandler(CopilotQuotaExceededException.class)
    ResponseEntity<QuotaExceededError> quotaExceeded(CopilotQuotaExceededException e) {
        ErrorCode code = ErrorCode.COPILOT_QUOTA_EXCEEDED;
        ApiError base = ApiErrors.of(code);
        return ResponseEntity.status(code.status())
                .body(new QuotaExceededError(base.code(), base.message(), base.details(), base.traceId(),
                        e.resetsAt()));
    }

    @ExceptionHandler(CopilotUnavailableException.class)
    ResponseEntity<ApiError> unavailable() {
        return respond(ErrorCode.COPILOT_UNAVAILABLE);
    }

    @ExceptionHandler(ContextNotFoundException.class)
    ResponseEntity<ApiError> contextNotFound() {
        return respond(ErrorCode.COPILOT_CONTEXT_NOT_FOUND);
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    ResponseEntity<ApiError> conversationNotFound() {
        return respond(ErrorCode.COPILOT_CONVERSATION_NOT_FOUND);
    }

    @ExceptionHandler(CopilotRequestNotFoundException.class)
    ResponseEntity<ApiError> requestNotFound() {
        return respond(ErrorCode.COPILOT_REQUEST_NOT_FOUND);
    }

    @ExceptionHandler(CopilotRequestInProgressException.class)
    ResponseEntity<ApiError> inProgress() {
        return respond(ErrorCode.COPILOT_REQUEST_IN_PROGRESS);
    }

    @ExceptionHandler(CopilotAlreadyAnsweredException.class)
    ResponseEntity<ApiError> alreadyAnswered() {
        return respond(ErrorCode.COPILOT_ALREADY_ANSWERED);
    }

    @ExceptionHandler(CopilotKeyReusedException.class)
    ResponseEntity<ApiError> keyReused() {
        return respond(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    /** Segundos inteiros, para cima e no mínimo 1: o header não aceita fração. */
    private static long seconds(Duration retryAfter) {
        long millis = retryAfter == null ? 0 : retryAfter.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
