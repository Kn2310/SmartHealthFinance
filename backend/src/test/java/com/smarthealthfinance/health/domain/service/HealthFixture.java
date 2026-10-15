package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Montagem de entradas do Health para os testes. {@link #example()} reproduz design/specs/09-dados-de-exemplo.md:
 * abr–ago com as receitas e despesas da tabela "Histórico", setembro de 1 a 22 com as categorias do mês e a reserva
 * de R$ 10.000,00 no Banco Norte. Os meses do histórico têm a parte variável no dia 28, fora do recorte 1–22.
 */
final class HealthFixture {

    static final Currency BRL = Currency.getInstance("BRL");
    static final Map<String, String> NAMES = Map.of("HOUSING", "Moradia", "DINING_OUT", "Alimentação fora",
            "GROCERIES", "Mercado", "TRANSPORT", "Transporte", "SUBSCRIPTIONS", "Assinaturas",
            "OTHER_EXPENSE", "Outros", "LEISURE", "Lazer", "SALARY", "Salário", "SELF_EMPLOYED",
            "Trabalho autônomo");

    private final List<FlowEntry> entries = new ArrayList<>();
    private final List<HealthInput.ReserveBalance> reserves = new ArrayList<>();
    private LocalDate firstActivity;

    static HealthFixture example() {
        HealthFixture fixture = new HealthFixture();
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

    HealthFixture add(String date, CashFlowRole role, String category, String merchant, String amount) {
        LocalDate day = LocalDate.parse(date);
        entries.add(new FlowEntry(day, role, category, merchant, merchant == null ? null : merchant.toUpperCase(),
                Money.of(amount, BRL), 1));
        if (firstActivity == null || day.isBefore(firstActivity)) {
            firstActivity = day;
        }
        return this;
    }

    HealthFixture reserve(String balance) {
        reserves.add(new HealthInput.ReserveBalance(UUID.randomUUID(), Money.of(balance, BRL)));
        return this;
    }

    HealthFixture noReserves() {
        reserves.clear();
        return this;
    }

    /** Mês corrente, parcial até {@code today}. */
    HealthInput asOf(String today) {
        return input(AnalysisPeriod.resolve(null, LocalDate.parse(today)));
    }

    /** Mês fechado, visto de {@code today}. */
    HealthInput closed(String month, String today) {
        return input(AnalysisPeriod.resolve(YearMonth.parse(month).toString(), LocalDate.parse(today)));
    }

    private HealthInput input(AnalysisPeriod period) {
        List<FlowEntry> visible = entries.stream().filter(e -> !e.day().isAfter(period.to())).toList();
        return new HealthInput(period, Optional.ofNullable(firstActivity), new FlowLedger(visible, BRL), reserves, 3,
                NAMES);
    }
}
