package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesEligibility;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastInput.InvoiceEventFact;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.AURORA;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Fachada do {@code forecast-v1} sobre o fixture de design/specs/09-dados-de-exemplo.md e cenários de borda. */
class ForecastCalculatorTest {

    /**
     * abr–ago com o histórico da tabela de 09 (mesmos números do fixture do Health) e setembro de 1 a 22; reserva e
     * contas somam R$ 18.420,35. Cada comerciante tem uma cobrança por mês; só o cinema (dia 28) varia mais de 10%.
     */
    private static ForecastFixture exampleData() {
        ForecastFixture fixture = new ForecastFixture();
        String[][] history = {
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
        return fixture;
    }

    private static ForecastResult exampleResult() {
        return ForecastCalculator.calculate(exampleData().input("2026-09-22", "2026-04-05", "18420.35"));
    }

    @Test
    void exampleDataDetectsTheSixMonthlySeriesAndTheyAreAllRealizedThisMonth() {
        ForecastResult result = exampleResult();

        assertThat(result.algorithmVersion()).isEqualTo("forecast-v1");
        assertThat(result.historyMonths()).isEqualTo(5);
        assertThat(result.limitations()).containsExactly(Limitation.SHORT_HISTORY);
        assertThat(result.series()).extracting(a -> a.series().key()).containsExactly("merchant:empresa ficticia",
                "merchant:entrega ja", "merchant:imobiliaria", "merchant:mercado bom preco", "merchant:posto via norte",
                "merchant:streamo");
        assertThat(result.series()).allSatisfy(a -> {
            assertThat(a.eligibility()).isEqualTo(SeriesEligibility.ELIGIBLE);
            assertThat(a.realizedThisMonth()).isTrue();
            assertThat(a.series().algorithmVersion()).isEqualTo("forecast-v1");
            assertThat(a.series().status()).isEqualTo(SeriesStatus.DETECTED);
        });
        // entrega ja: 912–1.021 (amplitude de 11,1% da mediana 980,00) → MEDIUM; as demais, valor fixo → HIGH
        assertThat(result.series().stream().filter(a -> a.series().key().equals("merchant:entrega ja")).findFirst()
                .orElseThrow().series().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(result.series().stream().filter(a -> a.series().key().equals("merchant:imobiliaria")).findFirst()
                .orElseThrow().series().confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void exampleDataMonthProjectionIsRealizedPlusTheTypicalRestOfTheMonth() {
        ForecastResult result = exampleResult();
        Projected expense = result.month().expense();

        // realizado 3.846,20 + cinema (dia 28) com mediana 700,10 [522,10–929,10] dos 5 meses
        assertThat(expense.basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(expense.factPart()).isEqualTo(brl("3846.20"));
        assertThat(expense.estimatedPart()).isEqualTo(brl("700.10"));
        assertThat(expense.value()).isEqualTo(brl("4546.30"));
        assertThat(expense.low()).isEqualTo(brl("4368.30"));
        assertThat(expense.high()).isEqualTo(brl("4775.30"));
        // amplitude de 407,00 > 25% de 700,10 → HIGH_VARIABILITY (no máximo LOW); 5 meses → SHORT_HISTORY
        assertThat(expense.confidence()).isEqualTo(Confidence.LOW);
        assertThat(expense.limitations()).containsExactly(Limitation.SHORT_HISTORY, Limitation.HIGH_VARIABILITY);
        assertThat(expense.evidence()).hasSize(5);

        assertThat(result.month().income().value()).isEqualTo(brl("6800.00"));
        assertThat(result.month().net().value()).isEqualTo(brl("2253.70"));
        assertThat(result.month().net().low()).isEqualTo(brl("2024.70"));
        assertThat(result.month().net().high()).isEqualTo(brl("2431.70"));
        assertThat(result.month().net().factPart()).isEqualTo(brl("2953.80"));
    }

    @Test
    void exampleDataNextIncomeEventsCashFlowAndCapacity() {
        ForecastResult result = exampleResult();

        assertThat(result.nextIncome().date()).isEqualTo(date("2026-10-05"));
        assertThat(result.nextIncome().amount().value()).isEqualTo(brl("6500.00"));
        assertThat(result.nextIncome().amount().confidence()).isEqualTo(Confidence.MEDIUM);

        assertThat(result.upcomingEvents()).extracting(e -> e.date() + " " + e.key() + " " + e.value().value()
                .toPlainString()).containsExactly(
                "2026-10-05 merchant:empresa ficticia 6500.00",
                "2026-10-05 merchant:imobiliaria 1980.00",
                "2026-10-10 merchant:streamo 39.90",
                "2026-10-12 merchant:entrega ja 980.00",
                "2026-10-20 merchant:mercado bom preco 320.00",
                "2026-10-21 merchant:posto via norte 170.00");

        assertThat(result.cashFlow().months()).extracting(m -> m.month().toString())
                .containsExactly("2026-09", "2026-10", "2026-11", "2026-12");
        assertThat(result.cashFlow().months()).allSatisfy(m -> {
            assertThat(m.closing().basis()).isEqualTo(Basis.ESTIMATED);
            assertThat(m.closing().factPart()).isEqualTo(brl("18420.35"));
            assertThat(m.closing().low().amount()).isLessThanOrEqualTo(m.closing().value().amount());
            assertThat(m.closing().high().amount()).isGreaterThanOrEqualTo(m.closing().value().amount());
        });
        assertThat(result.savingCapacity().monthly().basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(result.invoices()).isEmpty();
    }

    @Test
    void sameInputGivesTheSameResult() {
        assertThat(exampleResult()).isEqualTo(exampleResult());
    }

    @Test
    void theUserStateOfExistingSeriesSurvivesTheRecalculationAndDrivesTheProjection() {
        RecurringSeries rent = ForecastFixture.series(SeriesDirection.EXPENSE, "aluguel", 15, "1000.00", "950.00",
                "1050.00", Confidence.MEDIUM, SeriesStatus.CONFIRMED);
        RecurringSeries edited = new RecurringSeries(rent.direction(), rent.key(), rent.label(), rent.channel(),
                rent.periodicity(), rent.expectedDay(), rent.expectedAmount(), rent.amountRange(), rent.confidence(),
                rent.occurrences(), rent.algorithmVersion(), SeriesStatus.CONFIRMED, SeriesOrigin.USER, brl("1200.00"),
                null, Instant.parse("2026-06-01T12:00:00Z"));
        ForecastInput base = JuneScenario.input();
        ForecastInput input = new ForecastInput(base.today(), base.currency(), base.firstActivity(), base.entries(),
                List.of(edited), base.totalBalance(), base.cards(), base.invoiceEvents());

        ForecastResult result = ForecastCalculator.calculate(input);

        RecurringSeries kept = result.series().stream().map(SeriesAssessment::series)
                .filter(s -> s.key().equals("merchant:aluguel")).findFirst().orElseThrow();
        assertThat(kept.status()).isEqualTo(SeriesStatus.CONFIRMED);
        assertThat(kept.origin()).isEqualTo(SeriesOrigin.USER);
        assertThat(kept.userExpectedAmount()).isEqualTo(brl("1200.00"));
        assertThat(kept.userExpectedDay()).isNull();
        assertThat(kept.statusChangedAt()).isEqualTo(Instant.parse("2026-06-01T12:00:00Z"));
        // o valor editado vale como exato: 150,00 realizado + 1.200,00 + variável 150,00
        assertThat(result.month().expense().value()).isEqualTo(brl("1500.00"));
        assertThat(result.month().expense().low()).isEqualTo(brl("1500.00"));
        assertThat(result.month().expense().high()).isEqualTo(brl("1500.00"));
        assertThat(result.month().pending()).singleElement()
                .satisfies(p -> assertThat(p.confidence()).isEqualTo(Confidence.HIGH));
    }

    @Test
    void withoutEnoughHistoryThereAreOnlyFacts() {
        ForecastFixture fixture = new ForecastFixture()
                .add("2026-08-10", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "200.00")
                .add("2026-09-10", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "300.00");
        InvoiceEventFact fact = new InvoiceEventFact(AURORA, "Aurora", YearMonth.of(2026, 9),
                UpcomingEventKind.INVOICE_PARTIAL, date("2026-09-28"), brl("3412.80"));

        ForecastResult result = ForecastCalculator.calculate(fixture.input("2026-09-22", "2026-08-10", "1000.00",
                List.of(), List.of(ForecastFixture.aurora()), List.of(fact)));

        assertThat(result.limitations()).containsExactly(Limitation.INSUFFICIENT_HISTORY);
        assertThat(result.month().expense().basis()).isEqualTo(Basis.FACT);
        assertThat(result.nextIncome()).isNull();
        assertThat(result.cashFlow()).isNull();
        assertThat(result.savingCapacity()).isNull();
        assertThat(result.series()).isEmpty();
        assertThat(result.upcomingEvents()).singleElement()
                .satisfies(e -> assertThat(e.value().basis()).isEqualTo(Basis.FACT));
        assertThat(result.invoices()).allSatisfy(i -> assertThat(i.total().basis()).isEqualTo(Basis.FACT));
    }

    @Test
    void everyEstimatedValueCarriesBasisPartsRangeConfidenceAndEvidence() {
        ForecastResult result = ForecastCalculator.calculate(JuneScenario.input());

        List<Projected> estimated = new java.util.ArrayList<>(List.of(result.month().expense(), result.month().net(),
                result.nextIncome().amount(), result.savingCapacity().monthly()));
        result.cashFlow().months().forEach(m -> estimated.add(m.closing()));
        result.upcomingEvents().stream().map(e -> e.value()).filter(Projected::isEstimated).forEach(estimated::add);

        assertThat(estimated).isNotEmpty().allSatisfy(p -> {
            assertThat(p.basis()).isEqualTo(Basis.ESTIMATED);
            assertThat(p.value()).isEqualTo(p.factPart().plus(p.estimatedPart()));
            assertThat(p.low().amount()).isLessThanOrEqualTo(p.value().amount());
            assertThat(p.high().amount()).isGreaterThanOrEqualTo(p.value().amount());
            assertThat(p.confidence()).isNotNull();
        });
        assertThat(result.month().expense().evidence()).isNotEmpty();
        assertThat(result.nextIncome().amount().evidence()).isNotEmpty();
        assertThat(result.cashFlow().evidence()).isNotEmpty();
        assertThat(result.cashFlow().troughNext30().lowestCentral()).isNotNull();
    }
}
