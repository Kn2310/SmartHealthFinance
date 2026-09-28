package com.smarthealthfinance.shared.presentation.error;

import java.util.List;

import com.smarthealthfinance.shared.presentation.web.CorrelationIdFilter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

/**
 * Fábrica de {@link ApiError} que anexa o traceId da requisição corrente.
 */
public final class ApiErrors {

	private ApiErrors() {
	}

	public static ApiError of(ErrorCode code) {
		return of(code, code.defaultMessage(), List.of());
	}

	public static ApiError of(ErrorCode code, String message, List<ApiError.Detail> details) {
		return new ApiError(code.name(), message, details, currentTraceId());
	}

	/**
	 * TraceId do span OpenTelemetry corrente; na ausência de span, usa o correlationId da requisição.
	 */
	public static String currentTraceId() {
		SpanContext context = Span.current().getSpanContext();
		if (context.isValid()) {
			return context.getTraceId();
		}
		return MDC.get(CorrelationIdFilter.MDC_KEY);
	}

}
