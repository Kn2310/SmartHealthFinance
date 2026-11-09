package com.smarthealthfinance.consents.domain.repository;

import com.smarthealthfinance.consents.domain.model.ConsentAuditEntry;

/** Trilha só de inserção, gravada na mesma transação da mudança (ADR-0038 §5). */
public interface ConsentAuditLog {

    void append(ConsentAuditEntry entry);
}
