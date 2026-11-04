package com.smarthealthfinance.forecast.application.exception;

/** Cartão pedido no filtro inexistente OU de outro Workspace — indistinguíveis de propósito (ADR-0003, ADR-0032 §1). */
public final class ForecastCardNotFoundException extends RuntimeException {

    public ForecastCardNotFoundException() {
        super("Card not found");
    }
}
