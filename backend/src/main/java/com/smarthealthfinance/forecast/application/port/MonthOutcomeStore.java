package com.smarthealthfinance.forecast.application.port;

import com.smarthealthfinance.forecast.domain.model.MonthOutcome;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Previsto × realizado por mês (ADR-0031 §6). Sempre por Workspace; exige transação ativa. Nada aqui apaga linhas.
 */
public interface MonthOutcomeStore {

    /**
     * Congela a projeção do mês ({@code ON CONFLICT DO NOTHING}): a primeira gravação vence e nunca é reescrita.
     *
     * @return {@code true} se gravou, {@code false} se já existia
     */
    boolean freeze(WorkspaceId workspaceId, MonthOutcome frozen, Instant now);

    /**
     * Regrava só o realizado e o erro, enquanto a linha não foi liquidada. A projeção congelada não é tocada.
     *
     * @return {@code false} se não existe ou já está liquidada (imutável)
     */
    boolean reconcile(WorkspaceId workspaceId, MonthOutcome reconciled);

    /**
     * Liquida o mês ({@code settled_at}); depois disso a linha é imutável.
     *
     * @return {@code false} se não existe ou já estava liquidada
     */
    boolean settle(WorkspaceId workspaceId, YearMonth month, String algorithmVersion, Instant now);

    Optional<Stored> find(WorkspaceId workspaceId, YearMonth month, String algorithmVersion);

    /** Meses da versão ainda não liquidados, do mais antigo ao mais recente. */
    List<Stored> findUnsettled(WorkspaceId workspaceId, String algorithmVersion);

    /** Todos os meses da versão, do mais antigo ao mais recente. */
    List<Stored> findAll(WorkspaceId workspaceId, String algorithmVersion);

    /** @param settledAt nulo enquanto o realizado ainda é reconciliado */
    record Stored(MonthOutcome outcome, Instant frozenAt, Instant settledAt) {

        public boolean settled() {
            return settledAt != null;
        }
    }
}
