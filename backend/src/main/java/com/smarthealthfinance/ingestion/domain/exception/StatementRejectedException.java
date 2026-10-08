package com.smarthealthfinance.ingestion.domain.exception;

/**
 * O arquivo inteiro foi recusado (ADR-0009 §7): nenhum batch é criado.
 * O código é estável e nunca carrega conteúdo do arquivo.
 */
public class StatementRejectedException extends RuntimeException {

    private final String reason;

    public StatementRejectedException(String reason) {
        super("Statement rejected: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
