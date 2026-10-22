package com.smarthealthfinance.cards.application.exception;

/**
 * Cartão inexistente OU de outro Workspace — indistinguíveis de propósito (ADR-0003, ADR-0026 §1).
 */
public final class CardNotFoundException extends RuntimeException {
    public CardNotFoundException() {
        super("Card not found");
    }
}
