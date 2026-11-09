package com.smarthealthfinance.consents.domain.exception;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;

/** Ex.: guardar o histórico exige o uso de dados ativo (ADR-0038 §2). */
public final class ConsentPrerequisiteMissingException extends RuntimeException {
    public ConsentPrerequisiteMissingException(ConsentPurpose purpose) {
        super("Consent prerequisite missing for " + purpose);
    }
}
