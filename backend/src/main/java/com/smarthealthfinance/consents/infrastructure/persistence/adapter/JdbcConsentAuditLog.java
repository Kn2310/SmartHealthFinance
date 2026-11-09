package com.smarthealthfinance.consents.infrastructure.persistence.adapter;

import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.model.ConsentAuditEntry;
import com.smarthealthfinance.consents.domain.repository.ConsentAuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/** Só insere; a tabela recusa UPDATE por trigger (ADR-0038 §5). */
@Repository
public class JdbcConsentAuditLog implements ConsentAuditLog {

    private final JdbcTemplate jdbc;

    public JdbcConsentAuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(ConsentAuditEntry entry) {
        Consent consent = entry.consent();
        jdbc.update("""
                insert into consent_audit (id, consent_id, workspace_id, user_id, purpose, action, terms_version, cause,
                                           occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.id(), consent.id().value(), consent.workspaceId().value(), consent.userId().value(),
                consent.purpose().name(), entry.action().name(), consent.termsVersion(), entry.cause().name(),
                Timestamp.from(entry.occurredAt()));
    }
}
