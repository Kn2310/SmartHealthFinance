package com.smarthealthfinance.consents.domain.exception;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;

/** Conceder exige a versão vigente do termo (ADR-0038 §3). */
public final class ConsentTermsOutdatedException extends RuntimeException {
    public ConsentTermsOutdatedException(ConsentPurpose purpose) {
        super("Consent terms version is not current for " + purpose);
    }
}
