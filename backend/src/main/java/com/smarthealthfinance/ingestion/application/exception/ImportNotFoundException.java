package com.smarthealthfinance.ingestion.application.exception;

/**
 * Batch inexistente OU de outro Workspace — indistinguíveis de propósito (ADR-0003/0009).
 */
public final class ImportNotFoundException extends RuntimeException {
    public ImportNotFoundException() {
        super("Import not found");
    }
}
