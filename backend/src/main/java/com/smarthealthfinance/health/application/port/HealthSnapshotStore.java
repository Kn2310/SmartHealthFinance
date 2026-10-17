package com.smarthealthfinance.health.application.port;

import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Snapshot do Financial Health e marcação para recálculo (ADR-0018 §3/§4). Sempre por Workspace. Exige transação
 * ativa. A leitura fica em {@link HealthSnapshotReader}.
 */
public interface HealthSnapshotStore extends HealthSnapshotReader {

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
}
