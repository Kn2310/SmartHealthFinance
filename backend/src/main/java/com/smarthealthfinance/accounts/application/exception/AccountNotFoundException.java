package com.smarthealthfinance.accounts.application.exception;

/**
 * Conta inexistente OU de outro Workspace — indistinguíveis de propósito (ADR-0003/0004).
 */
public final class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException() {
        super("Account not found");
    }
}
