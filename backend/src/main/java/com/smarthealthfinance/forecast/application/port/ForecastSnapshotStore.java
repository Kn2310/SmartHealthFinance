package com.smarthealthfinance.forecast.application.port;

import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Snapshot do Forecast e marcação para recálculo (ADR-0031 §3/§4). Sempre por Workspace. Exige transação ativa. A
 * leitura fica em {@link ForecastReader}.
 */
public interface ForecastSnapshotStore extends ForecastReader {

    /**
     * Marca o Workspace como sujo: {@code due_at = min(now + quiet, dirty_since + maxWait)}. Cada nova marca adia o
     * recálculo, nunca além do teto. Bloqueia enquanto o Workspace estiver sendo recalculado.
     */
    void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait);

    /** Marca como vencido agora, sem debounce (ações em recorrências, ADR-0031 §5). Bloqueia como o markDirty. */
    void markDueNow(WorkspaceId workspaceId, Instant now);

    /** Marca como sujos todos os Workspaces que têm snapshot (job diário, ADR-0031 §6). @return quantos */
    int markAllWithSnapshotsDirty(Instant now, Duration quiet, Duration maxWait);

    /**
     * Pega e bloqueia um Workspace vencido ({@code due_at ≤ now}) que nenhuma outra instância esteja recalculando
     * ({@code FOR UPDATE SKIP LOCKED}); o lock vai até o fim da transação.
     */
    Optional<WorkspaceId> claimDue(Instant now);

    /** Bloqueia a linha do Workspace até o fim da transação (rebuild), criando-a se preciso. */
    void lock(WorkspaceId workspaceId);

    /**
     * Grava o snapshot da versão (substitui o da mesma versão), limpa a marca de sujo e registra {@code computedAt}.
     * Como o Workspace está bloqueado desde o início do recálculo, ninguém marcou no meio: a marca que existia é a
     * que este recálculo atendeu. Snapshots de outras versões não são tocados.
     */
    void replace(WorkspaceId workspaceId, ForecastResult result, Instant computedAt);

    /** {@code dirty_since} mais antigo entre todos os Workspaces marcados (métrica de freshness, ADR-0031 §9). */
    Optional<Instant> oldestDirtySince();

    /** Adia um Workspace cujo recálculo falhou, sem limpar a marca. */
    void postpone(WorkspaceId workspaceId, Instant dueAt);

    /**
     * @param all {@code false}: só Workspaces com transações e sem snapshot na versão; {@code true}: todos com
     *            transações
     */
    List<WorkspaceId> workspacesToRebuild(String algorithmVersion, boolean all);
}
