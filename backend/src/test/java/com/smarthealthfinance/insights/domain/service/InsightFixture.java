package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.health.application.service.HealthSnapshotCalculator;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.insights.domain.valueobject.EvaluationPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Entradas dos detectores para os testes. {@link #example()} reproduz design/specs/09-dados-de-exemplo.md (o mesmo
 * fixture do Health): abr–ago com o histórico, setembro de 1 a 22 e a reserva de R$ 10.000,00. O snapshot do Health
 * vem do cálculo real ({@link HealthSnapshotCalculator#calculate}) sobre as mesmas linhas, como na produção.
 */
final class InsightFixture {

    static final Currency BRL = Currency.getInstance("BRL");
    static final Map<String, String> NAMES = Map.of("HOUSING", "Moradia", "DINING_OUT", "Alimentação fora",
            "GROCERIES", "Mercado", "TRANSPORT", "Transporte", "SUBSCRIPTIONS", "Assinaturas",
            "OTHER_EXPENSE", "Outros", "LEISURE", "Lazer", "SALARY", "Salário", "SELF_EMPLOYED",
            "Trabalho autônomo", "SHOPPING", "Compras");

    private final List<FlowEntry> entries = new ArrayList<>();
    private final List<ExpenseItem> expenses = new ArrayList<>();
    private final List<HealthInput.ReserveBalance> reserves = new ArrayList<>();
    private LocalDate firstActivity;
    private boolean withHealth = true;

    static InsightFixture example() {
        InsightFixture fixture = new InsightFixture();
        String[][] history = {
                // mês, despesa variável fora do recorte (dia 28), alimentação fora (dia 12)
                {"2026-04", "598.10", "912.00"},
                {"2026-05", "796.10", "1004.00"},
                {"2026-06", "522.10", "948.00"},
                {"2026-07", "929.10", "1021.00"},
                {"2026-08", "700.10", "980.00"},
        };
        for (String[] month : history) {
            String m = month[0];
            fixture.add(m + "-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00");
            fixture.add(m + "-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1980.00");
            fixture.add(m + "-12", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", month[2]);
            fixture.add(m + "-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "320.00");
            fixture.add(m + "-21", CashFlowRole.EXPENSE, "TRANSPORT", "posto via norte", "170.00");
            fixture.add(m + "-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "39.90");
            fixture.add(m + "-28", CashFlowRole.EXPENSE, "LEISURE", "cinema", month[1]);
        }
        fixture.add("2026-07-18", CashFlowRole.INCOME, "SELF_EMPLOYED", "estudio ponto", "300.00");

        fixture.add("2026-09-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00");
        fixture.add("2026-09-18", CashFlowRole.INCOME, "SELF_EMPLOYED", "estudio ponto", "300.00");
        fixture.add("2026-09-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1800.00");
        fixture.add("2026-09-15", CashFlowRole.EXPENSE, "HOUSING", "energia", "186.32");
        fixture.add("2026-09-10", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "447.00");
        fixture.add("2026-09-12", CashFlowRole.EXPENSE, "DINING_OUT", "bistro lume", "412.80");
        fixture.add("2026-09-14", CashFlowRole.EXPENSE, "DINING_OUT", "pizzaria forno", "238.00");
        fixture.add("2026-09-16", CashFlowRole.EXPENSE, "DINING_OUT", "cafe grao", "186.20");
        fixture.add("2026-09-22", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "296.08");
        fixture.add("2026-09-20", CashFlowRole.EXPENSE, "TRANSPORT", "posto via norte", "220.00");
        fixture.add("2026-09-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "39.90");
        fixture.add("2026-09-11", CashFlowRole.EXPENSE, "OTHER_EXPENSE", null, "19.90");
        return fixture.reserve("10000.00");
    }

    InsightFixture add(String date, CashFlowRole role, String category, String merchant, String amount) {
        LocalDate day = LocalDate.parse(date);
        entries.add(new FlowEntry(day, role, category, merchant, merchant == null ? null : merchant.toUpperCase(),
                Money.of(amount, BRL), 1));
        if (firstActivity == null || day.isBefore(firstActivity)) {
            firstActivity = day;
        }
        return this;
    }

    /** Uma despesa individual ("maiores gastos") que também entra no fluxo de caixa. */
    InsightFixture expense(UUID id, String date, String category, String merchant, String amount) {
        add(date, CashFlowRole.EXPENSE, category, merchant, amount);
        expenses.add(new ExpenseItem(id, LocalDate.parse(date), "descricao livre", merchant == null ? null
                : merchant.toUpperCase(), category, Money.of(amount, BRL)));
        return this;
    }

    InsightFixture reserve(String balance) {
        reserves.add(new HealthInput.ReserveBalance(UUID.randomUUID(), Money.of(balance, BRL)));
        return this;
    }

    InsightFixture withoutHealth() {
        withHealth = false;
        return this;
    }

    /** A entrada como o loader monta, vista de {@code today}. */
    InsightInput asOf(String today) {
        LocalDate now = LocalDate.parse(today);
        EvaluationPeriod period = EvaluationPeriod.of(now);
        List<FlowEntry> visible = entries.stream().filter(e -> !e.day().isAfter(now)).toList();
        List<ExpenseItem> largest = expenses.stream()
                .filter(e -> period.period().range().contains(e.occurredOn()))
                .sorted(Comparator.comparing((ExpenseItem e) -> e.amount().amount()).reversed())
                .limit(InsightRules.UNUSUAL_CANDIDATES)
                .toList();
        return new InsightInput(period, Optional.ofNullable(firstActivity), new FlowLedger(visible, BRL), NAMES,
                largest, withHealth ? health(period, now) : Map.of());
    }

    List<InsightCandidate> detect(String today) {
        return InsightEngine.detect(asOf(today));
    }

    /** Snapshots do Health de P − 6 a P, como o {@code HealthSnapshotCalculator} grava (com a variação). */
    private Map<YearMonth, HealthResult> health(EvaluationPeriod period, LocalDate today) {
        if (firstActivity == null) {
            return Map.of();
        }
        YearMonth first = YearMonth.from(firstActivity);
        List<HealthInput> inputs = new ArrayList<>();
        for (int back = 7; back >= 0; back--) {
            YearMonth month = period.month().minusMonths(back);
            if (month.isBefore(first)) {
                continue;
            }
            AnalysisPeriod analysis = AnalysisPeriod.resolve(month.toString(), today);
            List<FlowEntry> visible = entries.stream().filter(e -> !e.day().isAfter(analysis.to())).toList();
            inputs.add(new HealthInput(analysis, Optional.of(firstActivity), new FlowLedger(visible, BRL), reserves,
                    3, NAMES));
        }
        Map<YearMonth, HealthResult> results = new HashMap<>();
        HealthSnapshotCalculator.calculate(inputs).forEach(r -> results.put(r.month(), r));
        return results;
    }
}
