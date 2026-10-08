package com.smarthealthfinance.ingestion.domain.exception;

import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;

public final class InvalidImportStatusTransitionException extends RuntimeException {

    public InvalidImportStatusTransitionException(ImportBatchId id, ImportStatus from, String action) {
        super("Import " + id + " in status " + from + " does not allow " + action);
    }
}
