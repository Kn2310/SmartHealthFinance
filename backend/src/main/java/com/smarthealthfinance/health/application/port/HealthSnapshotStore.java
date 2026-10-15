package com.smarthealthfinance.health.application.port;

import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Snapshot do Financial Health e marcação para recálculo (ADR-0018 §3/§4). Sempre por Workspace. Exige transação
 * ativa.
 */
public interface HealthSnapshotStore {

    /**
     * Marca o Workspace como sujo: {@code due_at = min(now + quiet, dirty_since + maxWait)}. Cada nova marca adia o
     * recálculo, nunca além do teto. Bloqueia enquanto o Workspace estiver sendo recalculado.
     */
    void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait);

    /** Marca como sujos todos os Workspaces que têm snapshot (job diário, ADR-0018 §5). @return quantos */
    int markAllWithSnapshotsDirty(Instant now, Duration quiet, Duration maxWait);

    /**
     * Pega e bloqueia um Workspace vencido ({@code due_at ≤ now}) que nenhuma outra instância esteja recalculando
     * ({@code FOR UPDATE SKIP LOCKED}); o lock vai até o fim da transação.
     */
    Optional<WorkspaceId> claimDue(Instant now);

    /** Bloqueia a linha do Workspace até o fim da transação (rebuild), criando-a se preciso. */
    void lock(WorkspaceId workspaceId);

    /**
     * Grava os resultados da versão (substitui os meses existentes e apaga os da mesma versão que não vieram), limpa a
     * marca de sujo e registra {@code computedAt}. Linhas de outras versões não são tocadas.
     */
    void replace(WorkspaceId workspaceId, String algorithmVersion, List<HealthResult> results, Instant computedAt);

    /** {@code dirty_since} mais antigo entre todos os Workspaces marcados (métrica de freshness, ADR-0018 §7). */
    Optional<Instant> oldestDirtySince();

    /** Adia um Workspace cujo recálculo falhou, sem limpar a marca. */
    void postpone(WorkspaceId workspaceId, Instant dueAt);

    /**
     * @param all {@code false}: só Workspaces com transações e sem snapshot na versão; {@code true}: todos com
     *            transações
     */
    List<WorkspaceId> workspacesToRebuild(String algorithmVersion, boolean all);

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
