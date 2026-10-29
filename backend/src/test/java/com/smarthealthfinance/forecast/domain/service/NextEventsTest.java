package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastInput.InvoiceEventFact;
import com.smarthealthfinance.forecast.domain.model.NextIncome;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.AURORA;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Próxima receita prevista (§3.2) e próximos eventos dos 30 dias (§3.3). */
class NextEventsTest {

    private static ProjectionContext context(ForecastInput input) {
        List<RecurringSeries> detected = RecurrenceDetector.detect(input.entries(), input.today());
        List<RecurringSeries> merged = RecurrenceMerge.merge(input.existingSeries(), detected);
        return ProjectionContext.of(input, SeriesAssessor.assess(merged, detected, input.entries(), input.today()));
    }

    private static List<UpcomingEvent> events(ForecastInput input) {
        ProjectionContext ctx = context(input);
        return UpcomingEventsProjector.project(ctx,
                CardInvoiceProjector.project(ctx, CashFlowProjector.horizonEnd(input.today())).projections());
    }

    private static Optional<NextIncome> nextIncome(ForecastInput input) {
        return NextIncomeProjector.project(context(input));
    }

    @Test
    void nextIncomeIsTheSalaryOfNextMonthWhenThisMonthsWasAlreadyReceived() {
        NextIncome next = nextIncome(JuneScenario.input()).orElseThrow();

        assertThat(next.date()).isEqualTo(date("2026-07-05"));
        assertThat(next.key()).isEqualTo("merchant:empresa");
        assertThat(next.amount().basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(next.amount().value()).isEqualTo(brl("5000.00"));
        assertThat(next.amount().low()).isEqualTo(brl("5000.00"));
        assertThat(next.amount().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(next.amount().evidence()).hasSize(3);
    }

    @Test
    void nextIncomeIsThisMonthsWhenItIsStillAhead() {
        ForecastInput input = new ForecastFixture()
                .monthly(CashFlowRole.INCOME, "empresa", 5, "SALARY", "2026-03=5000.00", "2026-04=5000.00",
                        "2026-05=5000.00")
                .input("2026-06-03", "2026-03-01", "0");
        assertThat(nextIncome(input).orElseThrow().date()).isEqualTo(date("2026-06-05"));
    }

    @Test
    void aSalaryThatIsLateGoesToNextMonth() {
        ForecastInput input = new ForecastFixture()
                .monthly(CashFlowRole.INCOME, "empresa", 5, "SALARY", "2026-03=5000.00", "2026-04=5000.00",
                        "2026-05=5000.00")
                .input("2026-06-10", "2026-03-01", "0");
        assertThat(nextIncome(input).orElseThrow().date()).isEqualTo(date("2026-07-05"));
    }

    @Test
    void theEarliestDateWinsAndTiesPickTheLargestAmount() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.INCOME, "pequena", 5, "SALARY", "2026-03=3000.00", "2026-04=3000.00",
                        "2026-05=3000.00")
                .monthly(CashFlowRole.INCOME, "grande", 5, "SALARY", "2026-03=5000.00", "2026-04=5000.00",
                        "2026-05=5000.00")
                .monthly(CashFlowRole.INCOME, "depois", 20, "SALARY", "2026-03=9000.00", "2026-04=9000.00",
                        "2026-05=9000.00");
        assertThat(nextIncome(fixture.input("2026-06-03", "2026-03-01", "0")).orElseThrow().key())
                .isEqualTo("merchant:grande");
    }

    @Test
    void noEligibleIncomeSeriesMeansNoNextIncome() {
        ForecastFixture fixture = new ForecastFixture().monthly(CashFlowRole.EXPENSE, "aluguel", 15, "HOUSING",
                "2026-03=1000.00", "2026-04=1000.00", "2026-05=1000.00");
        assertThat(nextIncome(fixture.input("2026-06-10", "2026-03-01", "0"))).isEmpty();
    }

    @Test
    void thirtyDayWindowIsInclusiveAndSeriesAlreadyRealizedAreSkipped() {
        // dia 10 (hoje, ainda não ocorreu) e dia 11 (hoje + 1); em 10/07 (hoje + 30) entra, em 11/07 (+31) não
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "academia", 10, "HEALTH", "2026-03=100.00", "2026-04=100.00",
                        "2026-05=100.00")
                .monthly(CashFlowRole.EXPENSE, "streamo", 11, "SUBSCRIPTIONS", "2026-03=40.00", "2026-04=40.00",
                        "2026-05=40.00")
                .monthly(CashFlowRole.EXPENSE, "internet", 8, "HOUSING", "2026-03=120.00", "2026-04=120.00",
                        "2026-05=120.00")
                // internet de junho já foi paga (dia 9, dentro de ±3 do dia 8)
                .add("2026-06-09", CashFlowRole.EXPENSE, "HOUSING", "internet", "120.00");
        List<UpcomingEvent> events = events(fixture.input("2026-06-10", "2026-03-01", "0"));

        assertThat(events).extracting(e -> e.date() + " " + e.key())
                .containsExactly("2026-06-10 merchant:academia", "2026-06-11 merchant:streamo",
                        "2026-07-08 merchant:internet", "2026-07-10 merchant:academia");
        assertThat(events).allSatisfy(e -> assertThat(e.value().basis()).isEqualTo(Basis.ESTIMATED));
        assertThat(events.getFirst().kind()).isEqualTo(UpcomingEventKind.RECURRING_EXPENSE);
    }

    @Test
    void ordersByDateThenFactsBeforeEstimatesThenLargestAmount() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "aluguel", 15, "HOUSING", "2026-03=1000.00", "2026-04=1000.00",
                        "2026-05=1000.00")
                .monthly(CashFlowRole.EXPENSE, "condominio", 15, "HOUSING", "2026-03=700.00", "2026-04=700.00",
                        "2026-05=700.00")
                .monthly(CashFlowRole.INCOME, "empresa", 5, "SALARY", "2026-03=5000.00", "2026-04=5000.00",
                        "2026-05=5000.00")
                .add("2026-06-05", CashFlowRole.INCOME, "SALARY", "empresa", "5000.00");
        List<InvoiceEventFact> facts = List.of(
                new InvoiceEventFact(AURORA, "Aurora", YearMonth.of(2026, 5), UpcomingEventKind.INVOICE_OPEN,
                        date("2026-06-15"), brl("800.00")),
                new InvoiceEventFact(AURORA, "Aurora", YearMonth.of(2026, 5), UpcomingEventKind.INVOICE_OVERDUE,
                        date("2026-06-12"), brl("100.00")));

        List<UpcomingEvent> events = events(fixture.input("2026-06-10", "2026-03-01", "0", List.of(), List.of(), facts));

        assertThat(events).extracting(e -> e.date() + " " + e.kind() + " " + e.value().value().toPlainString())
                .containsExactly(
                        "2026-06-12 INVOICE_OVERDUE 100.00",
                        // 15/06: o fato (800,00) antes das estimativas, e entre elas o maior valor primeiro
                        "2026-06-15 INVOICE_OPEN 800.00",
                        "2026-06-15 RECURRING_EXPENSE 1000.00",
                        "2026-06-15 RECURRING_EXPENSE 700.00",
                        "2026-07-05 RECURRING_INCOME 5000.00");
        assertThat(events.get(1).value().basis()).isEqualTo(Basis.FACT);
    }

    @Test
    void partialInvoiceFactKeepsItsValueAndGainsTheClosingEstimate() {
        ForecastFixture fixture = new ForecastFixture()
                .addCard("2026-06-24", "GROCERIES", "loja a", "100.00", AURORA, false)
                .addCard("2026-07-26", "GROCERIES", "loja b", "80.00", AURORA, false)
                .addCard("2026-08-25", "GROCERIES", "loja c", "120.00", AURORA, false);
        InvoiceEventFact partial = new InvoiceEventFact(AURORA, "Aurora Platinum", YearMonth.of(2026, 9),
                UpcomingEventKind.INVOICE_PARTIAL, date("2026-09-28"), brl("3412.80"));
        ForecastInput input = fixture.input("2026-09-22", "2026-03-01", "18420.35", List.of(),
                List.of(ForecastFixture.aurora()), List.of(partial));

        UpcomingEvent event = events(input).getFirst();

        assertThat(event.kind()).isEqualTo(UpcomingEventKind.INVOICE_PARTIAL);
        assertThat(event.value().basis()).isEqualTo(Basis.FACT);
        assertThat(event.value().partial()).isTrue();
        assertThat(event.value().value()).isEqualTo(brl("3412.80"));
        assertThat(event.closingEstimate().value()).isEqualTo(brl("3512.80"));
        assertThat(event.closingEstimate().basis()).isEqualTo(Basis.ESTIMATED);
    }

    @Test
    void withoutEnoughHistoryOnlyInvoiceFactsAppear() {
        ForecastFixture fixture = new ForecastFixture().add("2026-08-10", CashFlowRole.EXPENSE, "GROCERIES",
                "mercado", "100.00");
        InvoiceEventFact fact = new InvoiceEventFact(AURORA, "Aurora", YearMonth.of(2026, 9),
                UpcomingEventKind.INVOICE_PARTIAL, date("2026-09-28"), brl("100.00"));
        List<UpcomingEvent> events = events(fixture.input("2026-09-22", "2026-08-10", "0", List.of(), List.of(),
                List.of(fact)));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.value().basis()).isEqualTo(Basis.FACT);
            assertThat(e.closingEstimate()).isNull();
        });
    }
}
