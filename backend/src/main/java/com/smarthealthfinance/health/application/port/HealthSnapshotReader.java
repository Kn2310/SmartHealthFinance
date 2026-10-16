package com.smarthealthfinance.health.application.port;

import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Leitura do snapshot do Financial Health (ADR-0018 §4), sem os métodos de escrita: é a única parte do Health que
 * outro módulo (Insights, ADR-0022 §2) pode usar. Sempre por Workspace. Exige transação ativa.
 */
public interface HealthSnapshotReader {

    Optional<HealthResult> find(WorkspaceId workspaceId, String algorithmVersion, YearMonth month);

    /** Meses de {@code from} a {@code to} (inclusive) que existem, do mais antigo ao mais recente. */
    List<HealthResult> findMonths(WorkspaceId workspaceId, String algorithmVersion, YearMonth from, YearMonth to);

    SnapshotState state(WorkspaceId workspaceId, String algorithmVersion);

    /**
     * @param dirty            marcado para recálculo
     * @param computedAt       último recálculo; nulo se nunca
     * @param built            há snapshot na versão
     * @param latestAsOf       maior data-base gravada na versão; nula sem snapshot
     * @param hasTransactions  o Workspace tem transações
     * @param pendingEvents    eventos do Health ainda não marcados pelo consumidor (inclui DLQ)
     */
    record SnapshotState(boolean dirty, Instant computedAt, boolean built, LocalDate latestAsOf,
                         boolean hasTransactions, int pendingEvents) {}
}
