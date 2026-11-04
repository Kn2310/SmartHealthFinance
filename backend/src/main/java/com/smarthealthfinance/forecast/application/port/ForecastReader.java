package com.smarthealthfinance.forecast.application.port;

import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.SavingCapacity;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Leitura do snapshot do Forecast (ADR-0030 §7, ADR-0031 §1), sem os métodos de escrita: é a única parte do Forecast
 * que outro módulo (Insights, Goals e Simulation) pode usar. Sempre por Workspace. Exige transação ativa.
 * <p>
 * Tudo vem do snapshot gravado, na versão {@link ForecastCalculator#ALGORITHM_VERSION} (ou na pedida): nenhum cálculo
 * na leitura.
 */
public interface ForecastReader {

    /** O snapshot inteiro do Workspace na versão; vazio se nunca foi calculado. */
    Optional<ForecastResult> find(WorkspaceId workspaceId, String algorithmVersion);

    SnapshotState state(WorkspaceId workspaceId, String algorithmVersion);

    default Optional<ForecastResult> current(WorkspaceId workspaceId) {
        return find(workspaceId, ForecastCalculator.ALGORITHM_VERSION);
    }

    /** Fluxo de caixa projetado do saldo total (ADR-0030 §3.4); vazio sem snapshot ou sem histórico suficiente. */
    default Optional<CashFlowProjection> cashFlow(WorkspaceId workspaceId) {
        return current(workspaceId).map(ForecastResult::cashFlow);
    }

    /** Capacidade de aporte mensal (ADR-0030 §7), sempre {@code ESTIMATED}; vazio sem snapshot ou histórico. */
    default Optional<SavingCapacity> monthlySavingCapacity(WorkspaceId workspaceId) {
        return current(workspaceId).map(ForecastResult::savingCapacity);
    }

    /** As séries que entram nas projeções (elegíveis), como estavam no cálculo do snapshot. */
    default List<SeriesAssessment> recurringSeries(WorkspaceId workspaceId) {
        return current(workspaceId)
                .map(result -> result.series().stream().filter(SeriesAssessment::isEligible).toList())
                .orElse(List.of());
    }

    /**
     * @param dirty            marcado para recálculo
     * @param computedAt       último recálculo; nulo se nunca
     * @param built            há snapshot na versão
     * @param latestAsOf       data-base do snapshot na versão; nula sem snapshot
     * @param hasTransactions  o Workspace tem transações
     * @param pendingEvents    eventos do Forecast ainda não marcados pelo consumidor (inclui DLQ)
     */
    record SnapshotState(boolean dirty, Instant computedAt, boolean built, LocalDate latestAsOf,
                         boolean hasTransactions, int pendingEvents) {}
}
