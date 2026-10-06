package com.smarthealthfinance.shared.presentation.error;

import java.util.ArrayList;
import java.util.List;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Converte toda exceção da camada web no contrato {@link ApiError}.
 * Nunca expõe stack trace, mensagens internas ou payload da requisição.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiError.Detail> details = new ArrayList<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			details.add(new ApiError.Detail(error.getField(), error.getCode(), error.getDefaultMessage()));
		}
		ex.getBindingResult()
			.getGlobalErrors()
			.forEach(error -> details.add(new ApiError.Detail(null, error.getCode(), error.getDefaultMessage())));
		return validationFailed(details);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiError.Detail> details = new ArrayList<>();
		ex.getParameterValidationResults()
			.forEach(result -> result.getResolvableErrors()
				.forEach(error -> details.add(new ApiError.Detail(result.getMethodParameter().getParameterName(),
						error.getCodes() != null && error.getCodes().length > 0 ? error.getCodes()[0] : null,
						error.getDefaultMessage()))));
		return validationFailed(details);
	}

	/** Demais exceções do Spring MVC (404, 405, 415, JSON malformado...) passam por aqui. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ErrorCode code = ErrorCode.fromStatus(statusCode.value());
		if (statusCode.is5xxServerError()) {
			log.error("Request failed with {}", statusCode.value(), ex);
		}
		return ResponseEntity.status(statusCode).headers(headers).body(ApiErrors.of(code));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
		return respond(ErrorCode.FORBIDDEN);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
		return respond(ErrorCode.UNAUTHENTICATED);
	}

	/** Lock otimista (@Version): outra requisição alterou o agregado primeiro. */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ApiError> handleConcurrentModification(OptimisticLockingFailureException ex) {
		return respond(ErrorCode.CONFLICT);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		log.error("Unexpected error", ex);
		return respond(ErrorCode.INTERNAL_ERROR);
	}

	@ExceptionHandler(InvalidValueException.class)
	ResponseEntity<Object> handleInvalidValue(InvalidValueException ex) {
		return validationFailed(List.of(new ApiError.Detail(ex.field(), ex.reason(), "Valor inválido.")));
	}

	private static ResponseEntity<Object> validationFailed(List<ApiError.Detail> details) {
		ErrorCode code = ErrorCode.VALIDATION_FAILED;
		return ResponseEntity.status(code.status()).body(ApiErrors.of(code, code.defaultMessage(), details));
	}

	private static ResponseEntity<ApiError> respond(ErrorCode code) {
		return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
	}

}
