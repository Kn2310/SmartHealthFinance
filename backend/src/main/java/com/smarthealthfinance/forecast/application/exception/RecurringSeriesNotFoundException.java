package com.smarthealthfinance.forecast.application.exception;

/**
 * Série recorrente inexistente OU de outro Workspace — indistinguíveis de propósito (ADR-0003, ADR-0031 Security).
 */
public final class RecurringSeriesNotFoundException extends RuntimeException {

    public RecurringSeriesNotFoundException() {
        super("Recurring series not found");
    }
}
