package com.smarthealthfinance.analytics.domain.service;

import com.smarthealthfinance.analytics.domain.enums.ChangeKind;
import com.smarthealthfinance.analytics.domain.enums.OutflowGroup;
import com.smarthealthfinance.analytics.domain.enums.Recurrence;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Composição, decomposição da variação e fontes de receita com aritmética exata (ADR-0015 §5/§6). Setembro de
 * 1 a 22 com os valores de design/specs/09-dados-de-exemplo.md.
 */
class AnalysisCalculationsTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final AnalysisPeriod SEPTEMBER = AnalysisPeriod.resolve(null, LocalDate.parse("2026-09-22"));
    private static final AnalyticsPeriod SEP = SEPTEMBER.range();
    private static final AnalyticsPeriod AUG_1_22 = SEPTEMBER.window(YearMonth.of(2026, 8));

    private final List<FlowEntry> entries = new ArrayList<>();

    private void add(String date, CashFlowRole role, String category, String merchant, String amount) {
        entries.add(new FlowEntry(LocalDate.parse(date), role, category, merchant,
                merchant == null ? null : merchant.toUpperCase(), Money.of(amount, BRL), 1));
    }

    private FlowLedger september() {
        add("2026-09-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00");
        add("2026-09-18", CashFlowRole.INCOME, "SELF_EMPLOYED", "estudio ponto", "300.00");
        add("2026-09-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1800.00");
        add("2026-09-15", CashFlowRole.EXPENSE, "HOUSING", "energia", "186.32");
        add("2026-09-10", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "447.00");
        add("2026-09-12", CashFlowRole.EXPENSE, "DINING_OUT", "bistro lume", "412.80");
        add("2026-09-14", CashFlowRole.EXPENSE, "DINING_OUT", "pizzaria forno", "238.00");
        add("2026-09-16", CashFlowRole.EXPENSE, "DINING_OUT", "cafe grao", "186.20");
        add("2026-09-22", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "296.08");
        add("2026-09-20", CashFlowRole.EXPENSE, "TRANSPORT", "posto via norte", "220.00");
        add("2026-09-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "39.90");
        add("2026-09-11", CashFlowRole.EXPENSE, "OTHER_EXPENSE", null, "19.90");
        // Agosto: 1–22 soma 3.372,10 de despesas; o dia 28 fica fora do recorte.
        add("2026-08-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00");
        add("2026-08-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1800.00");
        add("2026-08-15", CashFlowRole.EXPENSE, "HOUSING", "energia", "180.00");
        add("2026-08-12", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "662.10");
        add("2026-08-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "330.00");
        add("2026-08-21", CashFlowRole.EXPENSE, "TRANSPORT", "posto via norte", "400.00");
        add("2026-08-28", CashFlowRole.EXPENSE, "LEISURE", "cinema", "817.90");
        return new FlowLedger(entries, BRL);
    }

    @Test
    void ledgerTotalsMatchTheSampleData() {
        FlowLedger ledger = september();

        assertThat(ledger.total(CashFlowRole.INCOME, SEP)).isEqualTo(Money.of("6800.00", BRL));
        assertThat(ledger.total(CashFlowRole.EXPENSE, SEP)).isEqualTo(Money.of("3846.20", BRL));
        assertThat(ledger.net(SEP)).isEqualTo(Money.of("2953.80", BRL));
        assertThat(ledger.total(CashFlowRole.EXPENSE, AUG_1_22)).isEqualTo(Money.of("3372.10", BRL));
        assertThat(ledger.byCategory(CashFlowRole.EXPENSE, SEP).get("DINING_OUT")).isEqualTo(Money.of("1284.00", BRL));
        assertThat(ledger.byCategory(CashFlowRole.EXPENSE, SEP).keySet())
                .containsExactly("HOUSING", "DINING_OUT", "GROCERIES", "TRANSPORT", "SUBSCRIPTIONS", "OTHER_EXPENSE");
        // Média por dia do layout D-AnalysisSpending: 3.846,20 / 22 e 3.372,10 / 22, 2 casas, HALF_EVEN.
        assertThat(ledger.divide(Money.of("3846.20", BRL), 22)).isEqualTo(Money.of("174.83", BRL));
        assertThat(ledger.divide(Money.of("3372.10", BRL), 22)).isEqualTo(Money.of("153.28", BRL));
        assertThat(FlowLedger.share(Money.of("1284.00", BRL), Money.of("3846.20", BRL))).hasToString("33.4");
        assertThat(FlowLedger.share(Money.of("2953.80", BRL), Money.of("6800.00", BRL))).hasToString("43.4");
    }

    @Test
    void compositionMatchesTheLayoutAndClosesExactly() {
        CashFlowComposition composition = CashFlowComposition.of(september(), SEP);

        assertThat(composition.inflows()).isEqualTo(Money.of("6800.00", BRL));
        assertThat(composition.outflows()).extracting(CashFlowComposition.Outflow::categoryCode)
                .containsExactly("HOUSING", "DINING_OUT", "GROCERIES", "TRANSPORT", null);
        CashFlowComposition.Outflow others = composition.outflows().getLast();
        assertThat(others.group()).isEqualTo(OutflowGroup.OTHERS);
        // "Assinaturas e outros − R$ 59,80" no layout D-Analysis.
        assertThat(others.amount()).isEqualTo(Money.of("59.80", BRL));
        assertThat(others.categories()).isEqualTo(2);
        assertThat(composition.net()).isEqualTo(Money.of("2953.80", BRL));
        Money outflows = composition.outflows().stream().map(CashFlowComposition.Outflow::amount)
                .reduce(Money.zero(BRL), Money::plus);
        assertThat(composition.inflows().minus(outflows)).isEqualTo(composition.net());
    }

    @Test
    void uncategorizedIsItsOwnLine() {
        add("2026-09-10", CashFlowRole.EXPENSE, null, "loja", "5000.00");
        CashFlowComposition composition = CashFlowComposition.of(september(), SEP);

        assertThat(composition.outflows().getFirst().group()).isEqualTo(OutflowGroup.UNCATEGORIZED);
    }

    @Test
    void changesAgainstThePreviousMonthAddUpExactlyToTheNetChange() {
        FlowLedger ledger = september();

        ChangeBreakdown changes = ChangeBreakdown.of(ledger, SEP, List.of(AUG_1_22));

        assertThat(changes.currentNet()).isEqualTo(Money.of("2953.80", BRL));
        assertThat(changes.baseNet()).isEqualTo(ledger.net(AUG_1_22));
        assertThat(sumOfImpacts(changes)).isEqualTo(changes.netDelta());
        ChangeBreakdown.Item income = item(changes, ChangeKind.INCOME, null);
        assertThat(income.delta()).isEqualTo(Money.of("300.00", BRL));
        ChangeBreakdown.Item dining = item(changes, ChangeKind.EXPENSE_CATEGORY, "DINING_OUT");
        assertThat(dining.delta()).isEqualTo(Money.of("621.90", BRL));
        assertThat(dining.impact()).isEqualTo(Money.of("-621.90", BRL));
        assertThat(dining.merchants()).extracting(ChangeBreakdown.MerchantChange::merchantKey)
                .containsExactly("bistro lume", "pizzaria forno", "entrega ja");
        // Lazer só existe depois do dia 22 de agosto: fora do recorte, não aparece.
        assertThat(changes.items()).noneMatch(i -> "LEISURE".equals(i.categoryCode()));
        // Maior impacto absoluto primeiro.
        assertThat(changes.items().getFirst()).isEqualTo(dining);
    }

    @Test
    void changesAgainstTheAverageAlsoCloseExactlyDespiteRounding() {
        FlowLedger ledger = september();
        // Três janelas com valores que não dividem exato por 3.
        add("2026-07-03", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "100.00");
        add("2026-06-03", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "100.01");
        FlowLedger withHistory = new FlowLedger(entries, BRL);
        List<AnalyticsPeriod> windows = List.of(SEPTEMBER.window(YearMonth.of(2026, 6)),
                SEPTEMBER.window(YearMonth.of(2026, 7)), AUG_1_22);

        ChangeBreakdown changes = ChangeBreakdown.of(withHistory, SEP, windows);

        assertThat(item(changes, ChangeKind.EXPENSE_CATEGORY, "GROCERIES").base())
                .isEqualTo(Money.of("176.67", BRL));
        assertThat(sumOfImpacts(changes)).isEqualTo(changes.netDelta());
        assertThat(changes.currentNet()).isEqualTo(ledger.net(SEP));
    }

    @Test
    void incomeSourcesAreRecurringOrOccasional() {
        add("2026-07-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00");
        add("2026-06-06", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00");
        add("2026-07-18", CashFlowRole.INCOME, "SELF_EMPLOYED", "estudio ponto", "300.00");
        FlowLedger ledger = september();

        List<IncomeSources.Source> sources = IncomeSources.of(ledger, SEPTEMBER);

        assertThat(sources).hasSize(2);
        IncomeSources.Source salary = sources.getFirst();
        assertThat(salary.merchantKey()).isEqualTo("empresa ficticia");
        assertThat(salary.merchantName()).isEqualTo("EMPRESA FICTICIA");
        assertThat(salary.recurrence()).isEqualTo(Recurrence.RECURRING);
        // Dia 05 em jul, ago e set; dia 06 só em jun.
        assertThat(salary.typicalDay()).isEqualTo(5);
        assertThat(salary.share()).hasToString("95.6");
        IncomeSources.Source freelance = sources.getLast();
        assertThat(freelance.recurrence()).isEqualTo(Recurrence.OCCASIONAL);
        assertThat(freelance.typicalDay()).isNull();
        assertThat(freelance.lastReceivedOn()).isEqualTo(LocalDate.parse("2026-09-18"));
        assertThat(freelance.categoryCode()).isEqualTo("SELF_EMPLOYED");
    }

    @Test
    void typicalDayTieGoesToTheEarliestDay() {
        entries.add(new FlowEntry(LocalDate.parse("2026-07-10"), CashFlowRole.INCOME, null, "aluguel", "ALUGUEL",
                Money.of("1.00", BRL), 1));
        entries.add(new FlowEntry(LocalDate.parse("2026-08-05"), CashFlowRole.INCOME, null, "aluguel", "ALUGUEL",
                Money.of("1.00", BRL), 1));
        entries.add(new FlowEntry(LocalDate.parse("2026-09-10"), CashFlowRole.INCOME, null, "aluguel", "ALUGUEL",
                Money.of("1.00", BRL), 1));
        entries.add(new FlowEntry(LocalDate.parse("2026-06-05"), CashFlowRole.INCOME, null, "aluguel", "ALUGUEL",
                Money.of("1.00", BRL), 1));

        assertThat(IncomeSources.of(new FlowLedger(entries, BRL), SEPTEMBER).getFirst().typicalDay()).isEqualTo(5);
    }

    private static Money sumOfImpacts(ChangeBreakdown changes) {
        return changes.items().stream().map(ChangeBreakdown.Item::impact).reduce(Money.zero(BRL), Money::plus);
    }

    private static ChangeBreakdown.Item item(ChangeBreakdown changes, ChangeKind kind, String category) {
        return changes.items().stream()
                .filter(i -> i.kind() == kind && java.util.Objects.equals(i.categoryCode(), category))
                .findFirst().orElseThrow();
    }
}
