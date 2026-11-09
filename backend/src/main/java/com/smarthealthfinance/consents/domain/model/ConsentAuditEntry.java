package com.smarthealthfinance.consents.domain.model;

import com.smarthealthfinance.consents.domain.enums.ConsentAction;
import com.smarthealthfinance.consents.domain.enums.ConsentCause;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Linha da trilha de auditoria, só de inserção (ADR-0038 §5). Sem IP nem user-agent. */
public record ConsentAuditEntry(UUID id, Consent consent, ConsentAction action, ConsentCause cause,
                                Instant occurredAt) {

    public ConsentAuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(consent, "consent");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
