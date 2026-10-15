package com.smarthealthfinance.analytics.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader.Query;
import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.enums.Trend;
import com.smarthealthfinance.analytics.domain.model.CategoryFlow;
import com.smarthealthfinance.analytics.domain.model.DailyFlow;
import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.model.MerchantFlow;
import com.smarthealthfinance.analytics.domain.model.MonthlyFlow;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.InMemoryCategoryRepository;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Queries da Análise sobre uma porta em memória, com "hoje" = 22/09/2026 em São Paulo e os valores de
 * design/specs/09-dados-de-exemplo.md (ADR-0015).
 */
class AnalysisQueriesTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    /** 22/09/2026 às 23h30 em São Paulo já é 23/09 em UTC: o mês e o "hoje" seguem o fuso de negócio. */
    private static final Instant NOW = Instant.parse("2026-09-23T02:30:00Z");

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final FakeReadModel readModel = new FakeReadModel();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AnalysisQueries queries = new AnalysisQueries(
            new AnalysisContextLoader(ctx.currentUserService, new WorkspaceAccessGuard(ctx.workspaces), readModel,
                    Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("America/Sao_Paulo")),
            readModel, new InMemoryCategoryRepository(), meters);

    private UUID ws() {
        return ctx.anasWorkspaceId();
    }

    private void add(String date, CashFlowRole role, String category, String merchant, String amount) {
        readModel.entries.add(new FlowEntry(LocalDate.parse(date), role, category, merchant,
                merchant == null ? null : merchant, Money.of(amount, BRL), 1));
    }

    private void sample() {
        add("2026-09-05", CashFlowRole.INCOME, "SALARY", "Empresa Ficticia", "6500.00");
        add("2026-09-18", CashFlowRole.INCOME, null, "Estudio Ponto", "300.00");
        add("2026-09-05", CashFlowRole.EXPENSE, "HOUSING", "Imobiliaria", "1800.00");
        add("2026-09-15", CashFlowRole.EXPENSE, "HOUSING", "Energia", "186.32");
        add("2026-09-10", CashFlowRole.EXPENSE, "DINING_OUT", "Entrega Ja", "447.00");
        add("2026-09-12", CashFlowRole.EXPENSE, "DINING_OUT", "Bistro Lume", "837.00");
        add("2026-09-22", CashFlowRole.EXPENSE, "GROCERIES", "Mercado Bom Preco", "296.08");
        add("2026-09-20", CashFlowRole.EXPENSE, "TRANSPORT", "Posto Via Norte", "220.00");
        add("2026-09-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "Streamo", "39.90");
        add("2026-09-11", CashFlowRole.EXPENSE, null, null, "19.90");
        add("2026-09-21", CashFlowRole.REFUND, "DINING_OUT", "Entrega Ja", "30.00");
        add("2026-08-05", CashFlowRole.INCOME, "SALARY", "Empresa Ficticia", "6500.00");
        add("2026-08-05", CashFlowRole.EXPENSE, "HOUSING", "Imobiliaria", "1800.00");
        add("2026-08-12", CashFlowRole.EXPENSE, "DINING_OUT", "Entrega Ja", "1572.10");
        add("2026-08-28", CashFlowRole.EXPENSE, "LEISURE", "Cinema", "817.90");
        add("2026-07-05", CashFlowRole.INCOME, "SALARY", "Empresa Ficticia", "6500.00");
        add("2026-07-08", CashFlowRole.EXPENSE, "HOUSING", "Imobiliaria", "1800.00");
    }

    @Test
    void overviewMatchesTheLayoutNumbersExactly() {
        sample();

        var view = queries.overview(ws(), Query.defaults());

        assertThat(view.meta().state()).isEqualTo(DataState.READY);
        assertThat(view.meta().period().to()).isEqualTo(LocalDate.parse("2026-09-22"));
        assertThat(view.income().amount()).isEqualTo(Money.of("6800.00", BRL));
        assertThat(view.income().vsPrevious().percent()).hasToString("4.6");
        assertThat(view.expense().amount()).isEqualTo(Money.of("3846.20", BRL));
        // Agosto de 1 a 22: 1.800,00 + 1.572,10 = 3.372,10 (o dia 28 fica fora do recorte).
        assertThat(view.expense().vsPrevious().base()).isEqualTo(Money.of("3372.10", BRL));
        assertThat(view.expense().vsPrevious().percent()).hasToString("14.1");
        assertThat(view.expense().vsPrevious().trend()).isEqualTo(Trend.UP);
        assertThat(view.refunds().amount()).isEqualTo(Money.of("30.00", BRL));
        assertThat(view.net().amount()).isEqualTo(Money.of("2983.80", BRL));
        assertThat(view.savedShare()).hasToString("43.9");
        assertThat(view.months()).hasSize(6);
        assertThat(view.months().getLast().partial()).isTrue();
        assertThat(view.months().get(4).expense()).isEqualTo(Money.of("4190.00", BRL));
        assertThat(view.topCategories()).extracting(line -> line.categoryCode())
                .containsExactly("HOUSING", "DINING_OUT", "GROCERIES", "TRANSPORT");
        assertThat(view.topCategories().get(1).refunds()).isEqualTo(Money.of("30.00", BRL));
        assertThat(view.composition().net()).isEqualTo(view.net().amount());
        assertThat(view.changes().items().stream().map(i -> i.impact()).reduce(Money.zero(BRL), Money::plus))
                .isEqualTo(view.changes().netDelta());
    }

    @Test
    void averageComparisonUsesOnlyMonthsWithHistory() {
        sample();

        var view = queries.overview(ws(), new Query(null, "AVERAGE", 5));

        // Primeiro lançamento em julho: a média usa jul e ago (1–22), não 5 meses.
        assertThat(view.meta().comparison().monthsUsed()).isEqualTo(2);
        assertThat(view.expense().vsAverage().base()).isEqualTo(Money.of("2586.05", BRL));
        assertThat(view.changes().baseNet())
                .isEqualTo(Money.of("6500.00", BRL).minus(Money.of("2586.05", BRL)));
    }

    @Test
    void spendingHasDailyAveragesCumulativeAndLargestExpenses() {
        sample();

        var view = queries.spending(ws(), Query.defaults());

        assertThat(view.dailyAverage()).isEqualTo(Money.of("174.83", BRL));
        assertThat(view.previousDailyAverage()).isEqualTo(Money.of("153.28", BRL));
        assertThat(view.cumulative()).hasSize(22);
        assertThat(view.cumulative().getLast().amount()).isEqualTo(Money.of("3846.20", BRL));
        assertThat(view.previousCumulative()).hasSize(31);
        assertThat(view.previousCumulative().getLast().amount()).isEqualTo(Money.of("4190.00", BRL));
        assertThat(view.largestExpenses()).hasSize(1);
        assertThat(view.categories()).extracting(line -> line.categoryCode()).endsWith((String) null);
    }

    @Test
    void incomeListsSourcesWithRecurrence() {
        sample();

        var view = queries.income(ws(), Query.defaults());

        assertThat(view.sources()).extracting(s -> s.merchantKey()).containsExactly("Empresa Ficticia", "Estudio Ponto");
        assertThat(view.sources().getFirst().recurrence().name()).isEqualTo("RECURRING");
        assertThat(view.sources().getFirst().typicalDay()).isEqualTo(5);
        assertThat(view.monthlyAverage()).isEqualTo(Money.of("6500.00", BRL));
    }

    @Test
    void categoryDetailHasTicketHistoryAndMerchants() {
        sample();

        var view = queries.category(ws(), "DINING_OUT", Query.defaults());

        assertThat(view.amount().amount()).isEqualTo(Money.of("1284.00", BRL));
        assertThat(view.transactionCount()).isEqualTo(2);
        assertThat(view.averageTicket()).isEqualTo(Money.of("642.00", BRL));
        assertThat(view.history()).hasSize(6);
        assertThat(view.history().get(4).amount()).isEqualTo(Money.of("1572.10", BRL));
        assertThat(view.merchants()).extracting(m -> m.merchantKey()).containsExactly("Bistro Lume", "Entrega Ja");
        assertInvalidValue(() -> queries.category(ws(), "UNKNOWN", Query.defaults()), "categoryCode", "INVALID");
        assertInvalidValue(() -> queries.category(ws(), "dining", Query.defaults()), "categoryCode", "INVALID");
    }

    @Test
    void merchantsAreRankedByAmountOrCount() {
        sample();

        var byAmount = queries.merchants(ws(), Query.defaults(), null);
        var byCount = queries.merchants(ws(), Query.defaults(), "count");

        assertThat(byAmount.merchants().getFirst().merchantKey()).isEqualTo("Imobiliaria");
        assertThat(byAmount.merchants().getFirst().vsPrevious().trend()).isEqualTo(Trend.STABLE);
        assertThat(byAmount.unidentified()).isEqualTo(Money.of("19.90", BRL));
        assertThat(byCount.sort()).isEqualTo("COUNT");
        assertInvalidValue(() -> queries.merchants(ws(), Query.defaults(), "name"), "sort", "INVALID");
    }

    @Test
    void evolutionDefaultsToSixMonthsAndValidatesTheRange() {
        sample();

        assertThat(queries.evolution(ws(), Query.defaults(), null).months()).hasSize(6);
        assertThat(queries.evolution(ws(), Query.defaults(), 3).months()).extracting(m -> m.month().toString())
                .containsExactly("2026-07", "2026-08", "2026-09");
        assertInvalidValue(() -> queries.evolution(ws(), Query.defaults(), 13), "months", "OUT_OF_RANGE");
    }

    @Test
    void statesWithoutDataOrActivity() {
        var empty = queries.overview(ws(), Query.defaults());
        assertThat(empty.meta().state()).isEqualTo(DataState.NO_DATA);
        assertThat(empty.income()).isNull();
        assertThat(empty.composition()).isNull();

        add("2026-07-05", CashFlowRole.INCOME, "SALARY", "Empresa Ficticia", "6500.00");
        var quiet = queries.overview(ws(), Query.defaults());
        assertThat(quiet.meta().state()).isEqualTo(DataState.NO_ACTIVITY_IN_PERIOD);
        assertThat(quiet.income().amount()).isEqualTo(Money.zero(BRL));
    }

    @Test
    void comparisonIsUnavailableBeforeTheFirstActivity() {
        add("2026-09-05", CashFlowRole.INCOME, "SALARY", "Empresa Ficticia", "6500.00");

        var view = queries.overview(ws(), Query.defaults());

        assertThat(view.income().vsPrevious()).isNull();
        assertThat(view.income().vsAverage()).isNull();
        assertThat(view.changes()).isNull();
    }

    @Test
    void validatesParametersOnlyAfterAuthorization() {
        assertInvalidValue(() -> queries.overview(ws(), new Query("2026-10", null, null)), "month", "FUTURE");
        assertInvalidValue(() -> queries.overview(ws(), new Query(null, "MEDIA", null)), "compareWith", "INVALID");
        assertInvalidValue(() -> queries.overview(ws(), new Query(null, null, 0)), "averageMonths", "OUT_OF_RANGE");

        ctx.actAs(ctx.bob);
        assertThatThrownBy(() -> queries.overview(ws(), new Query("not-a-month", null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void recordsAQueryTimerPerView() {
        queries.overview(ws(), Query.defaults());

        assertThat(meters.find("shf.analytics.query").tag("view", "overview").tag("state", "NO_DATA").timer())
                .isNotNull();
    }

    /** Porta em memória: só as linhas e a primeira atividade importam para as queries. */
    private final class FakeReadModel implements AnalyticsReadModel {

        final List<FlowEntry> entries = new ArrayList<>();

        @Override
        public List<FlowEntry> entries(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
            return entries.stream().filter(e -> period.contains(e.day())).toList();
        }

        @Override
        public Map<String, String> categoryNames() {
            return Map.of();
        }

        @Override
        public Optional<LocalDate> firstActivity(WorkspaceId workspaceId) {
            return entries.stream().map(FlowEntry::day).min(LocalDate::compareTo);
        }

        @Override
        public List<ExpenseItem> largestExpenses(WorkspaceId workspaceId, AnalyticsPeriod period, int limit,
                                                 Currency currency) {
            return List.of(new ExpenseItem(UUID.randomUUID(), LocalDate.parse("2026-09-05"), "Aluguel", "Imobiliaria",
                    "HOUSING", Money.of("1800.00", BRL)));
        }

        @Override
        public ProjectionFreshness freshness(WorkspaceId workspaceId) {
            return new ProjectionFreshness(ProjectionState.UP_TO_DATE, NOW, 0);
        }

        @Override
        public List<MonthlyFlow> monthly(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
            return List.of();
        }

        @Override
        public List<CategoryFlow> byCategory(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
            return List.of();
        }

        @Override
        public List<MerchantFlow> byMerchant(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
            return List.of();
        }

        @Override
        public List<DailyFlow> daily(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
            return List.of();
        }
    }
}
