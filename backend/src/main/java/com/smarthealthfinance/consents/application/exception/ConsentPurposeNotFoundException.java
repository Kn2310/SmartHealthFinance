package com.smarthealthfinance.consents.application.exception;

/** Finalidade desconhecida na rota (ADR-0038 §7). */
public final class ConsentPurposeNotFoundException extends RuntimeException {
    public ConsentPurposeNotFoundException() {
        super("Consent purpose not found");
    }
}
