package com.smarthealthfinance.transactions.application.exception;

/**
 * Transação inexistente OU de outro Workspace — indistinguíveis de propósito (ADR-0003/0005).
 */
public final class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException() {
        super("Transaction not found");
    }
}
