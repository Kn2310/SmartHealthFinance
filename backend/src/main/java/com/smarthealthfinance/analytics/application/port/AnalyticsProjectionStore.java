package com.smarthealthfinance.analytics.application.port;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Lado de escrita da projeção de Analytics (ADR-0014 §3). Todo recálculo relê {@code transactions}, a fonte da
 * verdade: a projeção nunca soma deltas. Exige transação ativa.
 */
public interface AnalyticsProjectionStore {

    /**
     * Garante a linha de estado do Workspace e a bloqueia até o fim da transação: rebuild e recálculos do mesmo
     * Workspace ficam serializados (nunca dois delete + insert concorrentes no mesmo mês).
     *
     * @return a versão com que a projeção foi construída; vazio se nunca foi
     */
    OptionalInt lockWorkspace(WorkspaceId workspaceId);

    /** Quando o mês foi recalculado pela última vez; vazio se nunca foi. */
    Optional<Instant> monthRefreshedAt(WorkspaceId workspaceId, YearMonth month);

    /** Apaga e recalcula as linhas do mês a partir de {@code transactions}; registra {@code refreshedAt}. */
    void recomputeMonth(WorkspaceId workspaceId, YearMonth month, Instant refreshedAt);

    /** Apaga tudo do Workspace e recalcula todos os meses com transações; marca a {@code version}. */
    void rebuild(WorkspaceId workspaceId, int version, Instant rebuiltAt);

    /**
     * @param all {@code false}: só Workspaces com transações cuja projeção não existe ou é de outra versão;
     *            {@code true}: todos os Workspaces com transações
     */
    List<WorkspaceId> workspacesToRebuild(int version, boolean all);
}
