package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
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
 * @param cards           cartões ativos acompanhados, com a posição de hoje (ADR-0028 §1)
 * @param cardsAtMonthEnd posição dos cartões no último dia de cada um dos 6 meses anteriores ao corrente
 */
public record InsightInput(EvaluationPeriod period, Optional<LocalDate> firstActivity, FlowLedger ledger,
                           Map<String, String> categoryNames, List<ExpenseItem> largestExpenses,
                           Map<YearMonth, HealthResult> health, List<CardPosition> cards,
                           Map<YearMonth, List<CardPosition>> cardsAtMonthEnd) {

    public InsightInput(EvaluationPeriod period, Optional<LocalDate> firstActivity, FlowLedger ledger,
                        Map<String, String> categoryNames, List<ExpenseItem> largestExpenses,
                        Map<YearMonth, HealthResult> health) {
        this(period, firstActivity, ledger, categoryNames, largestExpenses, health, List.of(), Map.of());
    }

    public InsightInput {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(firstActivity, "firstActivity");
        Objects.requireNonNull(ledger, "ledger");
        categoryNames = Map.copyOf(categoryNames);
        largestExpenses = List.copyOf(largestExpenses);
        health = Map.copyOf(health);
        cards = List.copyOf(cards);
        cardsAtMonthEnd = Map.copyOf(cardsAtMonthEnd);
    }
}
