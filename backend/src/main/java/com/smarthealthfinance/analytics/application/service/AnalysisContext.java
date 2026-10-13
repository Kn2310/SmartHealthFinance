package com.smarthealthfinance.analytics.application.service;

import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.model.ComparisonPlan;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;

import java.util.Currency;
import java.util.Objects;
import java.util.function.Function;

/**
 * Tudo o que uma consulta da Análise precisa, carregado uma vez (ADR-0015 §1): Workspace autorizado, período, bases
 * de comparação, as linhas do fluxo de caixa do intervalo inteiro (período + bases + histórico) e a freshness.
 */
public record AnalysisContext(WorkspaceId workspaceId, Currency currency, AnalysisPeriod period, ComparisonPlan plan,
                              FlowLedger ledger, ProjectionFreshness freshness, DataState state) {

    public AnalysisContext {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(state, "state");
    }

    public AnalyticsPeriod current() {
        return period.range();
    }

    public boolean hasData() {
        return state != DataState.NO_DATA;
    }

    /** O valor no mesmo recorte do mês anterior; nulo sem base. */
    public Money previous(Function<AnalyticsPeriod, Money> value) {
        return plan.hasPrevious() ? value.apply(plan.previous()) : null;
    }

    /** A média mensal do valor nos meses de comparação; nula sem base. */
    public Money average(Function<AnalyticsPeriod, Money> value) {
        return ledger.average(plan.averageWindows(), value);
    }

    /** Valor do período com as variações contra as duas bases. */
    public Metric metric(Function<AnalyticsPeriod, Money> value) {
        Money amount = value.apply(current());
        return new Metric(amount, Variation.of(amount, previous(value)), Variation.of(amount, average(value)));
    }

    /**
     * @param vsPrevious nula sem o mês anterior
     * @param vsAverage  nula sem meses de comparação
     */
    public record Metric(Money amount, Variation vsPrevious, Variation vsAverage) {
    }
}
