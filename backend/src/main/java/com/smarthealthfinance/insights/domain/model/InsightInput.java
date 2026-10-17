package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.insights.domain.valueobject.EvaluationPeriod;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Entrada dos detectores (ADR-0022 §4), montada só pelas portas de Analytics e Health.
 *
 * @param ledger          linhas do fluxo de caixa de P − 7 meses até hoje
 * @param categoryNames   nome de exibição de cada categoria do catálogo
 * @param largestExpenses as maiores despesas individuais de P
 * @param health          snapshots do Health por mês (P − 6 a P), na versão atual; meses sem snapshot não aparecem
 */
public record InsightInput(EvaluationPeriod period, Optional<LocalDate> firstActivity, FlowLedger ledger,
                           Map<String, String> categoryNames, List<ExpenseItem> largestExpenses,
                           Map<YearMonth, HealthResult> health) {

    public InsightInput {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(firstActivity, "firstActivity");
        Objects.requireNonNull(ledger, "ledger");
        categoryNames = Map.copyOf(categoryNames);
        largestExpenses = List.copyOf(largestExpenses);
        health = Map.copyOf(health);
    }
}
