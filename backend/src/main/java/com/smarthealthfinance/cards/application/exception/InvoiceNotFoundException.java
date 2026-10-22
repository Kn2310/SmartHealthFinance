package com.smarthealthfinance.cards.application.exception;

/** Fatura passada sem lançamentos, ou mês sem fatura com esse vencimento (ADR-0026 §4). */
public final class InvoiceNotFoundException extends RuntimeException {
    public InvoiceNotFoundException() {
        super("Invoice not found");
    }
}
