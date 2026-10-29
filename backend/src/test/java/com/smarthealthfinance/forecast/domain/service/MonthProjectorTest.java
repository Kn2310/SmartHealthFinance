package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.YearMonth;
import java.util.List;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static org.assertj.core.api.Assertions.assertThat;

/** Projeção do mês e resultado projetado (ADR-0030 §3.1). */
class MonthProjectorTest {

    private static ProjectionContext context(ForecastInput input) {
        List<RecurringSeries> detected = RecurrenceDetector.detect(input.entries(), input.today());
        List<RecurringSeries> merged = RecurrenceMerge.merge(input.existingSeries(), detected);
        return ProjectionContext.of(input, SeriesAssessor.assess(merged, detected, input.entries(), input.today()));
    }

    /**
     * Fixture de design/specs/09-dados-de-exemplo.md: com agosto como único mês da base,
     * 3.846,20 + (4.190,00 − 3.372,10) = R$ 4.664,10 (e não os "≈ R$ 4.600" do layout).
     */
    @Test
    void exampleDataProjectsTheMonthAt4664_10WithAugustAsTheBase() {
        ForecastFixture fixture = new ForecastFixture()
                // agosto: 1–22 somam 3.372,10 e 23–31 somam 817,90 (total 4.190,00)
                .add("2026-08-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1800.00")
                .add("2026-08-12", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "1000.00")
                .add("2026-08-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "572.10")
                .add("2026-08-25", CashFlowRole.EXPENSE, "LEISURE", "cinema", "500.00")
                .add("2026-08-30", CashFlowRole.EXPENSE, "TRANSPORT", "posto via norte", "317.90")
                .add("2026-08-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00")
                // setembro até o dia 22: 3.846,20 de despesa e 6.800,00 de receita
                .add("2026-09-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1800.00")
                .add("2026-09-10", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "1000.00")
                .add("2026-09-15", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "600.00")
                .add("2026-09-22", CashFlowRole.EXPENSE, "TRANSPORT", "posto via norte", "446.20")
                .add("2026-09-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "6500.00")
                .add("2026-09-18", CashFlowRole.INCOME, "SELF_EMPLOYED", "estudio ponto", "300.00");
        ForecastInput input = fixture.input("2026-09-22", "2026-08-01", "18420.35");

        MonthProjection projection = MonthProjector.project(context(input), List.of(YearMonth.of(2026, 8)));

        assertThat(projection.month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(projection.baseMonths()).isEqualTo(1);
        assertThat(projection.expense().basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(projection.expense().value()).isEqualTo(brl("4664.10"));
        assertThat(projection.expense().factPart()).isEqualTo(brl("3846.20"));
        assertThat(projection.expense().estimatedPart()).isEqualTo(brl("817.90"));
        assertThat(projection.expense().low()).isEqualTo(brl("4664.10"));
        assertThat(projection.expense().high()).isEqualTo(brl("4664.10"));
        assertThat(projection.expense().evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.month()).isEqualTo(YearMonth.of(2026, 8));
            assertThat(evidence.value()).isEqualTo(brl("817.90"));
        });

        // resultado: receitas realizadas (6.800,00) − despesa projetada; o fato é o resultado de hoje (+2.953,80)
        assertThat(projection.income().basis()).isEqualTo(Basis.FACT);
        assertThat(projection.income().value()).isEqualTo(brl("6800.00"));
        assertThat(projection.net().basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(projection.net().value()).isEqualTo(brl("2135.90"));
        assertThat(projection.net().factPart()).isEqualTo(brl("2953.80"));
        assertThat(projection.net().estimatedPart()).isEqualTo(brl("-817.90"));
    }

    @Test
    void theLinearPaceIsNotUsed() {
        // 3.846,20 ÷ 22 × 30 = 5.244,82 (ritmo linear) — o ADR rejeita e usa o restante típico do mês
        assertThat(brl("4664.10").amount()).isLessThan(brl("5244.82").amount());
    }

    @Test
    void junePendingRecurringAndRemainingVariable() {
        MonthProjection projection = MonthProjector.project(context(JuneScenario.input()));

        assertThat(projection.baseMonths()).isEqualTo(3);
        // realizado 150,00 (08/06) + aluguel pendente 1.000,00 [950–1.050] + variável restante 150,00 (dia 20)
        assertThat(projection.expense().value()).isEqualTo(brl("1300.00"));
        assertThat(projection.expense().factPart()).isEqualTo(brl("150.00"));
        assertThat(projection.expense().estimatedPart()).isEqualTo(brl("1150.00"));
        assertThat(projection.expense().low()).isEqualTo(brl("1250.00"));
        assertThat(projection.expense().high()).isEqualTo(brl("1350.00"));
        assertThat(projection.expense().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(projection.expense().limitations()).containsExactly(Limitation.SHORT_HISTORY);
        assertThat(projection.pending()).singleElement().satisfies(item -> {
            assertThat(item.direction()).isEqualTo(SeriesDirection.EXPENSE);
            assertThat(item.date()).isEqualTo(ForecastFixture.date("2026-06-15"));
            assertThat(item.expected()).isEqualTo(brl("1000.00"));
        });

        // o salário de junho já foi recebido: receita é fato e o resultado projetado tem as partes
        assertThat(projection.income().basis()).isEqualTo(Basis.FACT);
        assertThat(projection.income().partial()).isTrue();
        assertThat(projection.net().value()).isEqualTo(brl("3700.00"));
        assertThat(projection.net().factPart()).isEqualTo(brl("4850.00"));
        assertThat(projection.net().estimatedPart()).isEqualTo(brl("-1150.00"));
        assertThat(projection.net().low()).isEqualTo(brl("3650.00"));
        assertThat(projection.net().high()).isEqualTo(brl("3750.00"));
    }

    @Test
    void pendingRecurringIncomeEntersTheIncomeAndTheResult() {
        // o salário de junho ainda não caiu (hoje é dia 3)
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.INCOME, "empresa", 5, "SALARY", "2026-03=5000.00", "2026-04=5000.00",
                        "2026-05=5000.00")
                .add("2026-03-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "100.00")
                .add("2026-04-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "100.00")
                .add("2026-05-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "100.00");
        MonthProjection projection = MonthProjector.project(context(fixture.input("2026-06-03", "2026-03-01", "0")));

        assertThat(projection.income().basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(projection.income().factPart()).isEqualTo(brl("0"));
        assertThat(projection.income().estimatedPart()).isEqualTo(brl("5000.00"));
        assertThat(projection.income().confidence()).isEqualTo(Confidence.MEDIUM);
        // despesa: nada realizado + variável restante (mercado, mediana 100,00 depois do dia 3)
        assertThat(projection.expense().value()).isEqualTo(brl("100.00"));
        assertThat(projection.net().value()).isEqualTo(brl("4900.00"));
    }

    @Test
    void aRealizedSeriesIsNotProjectedAgain() {
        ForecastFixture fixture = JuneScenario.fixture().add("2026-06-14", CashFlowRole.EXPENSE, "HOUSING",
                "aluguel", "1000.00");
        MonthProjection projection = MonthProjector.project(context(fixture.input("2026-06-10", "2026-03-01",
                "10000.00")));
        // 14/06 está depois de hoje (10/06) para o razão de hoje: a entrada futura não conta como realizada
        assertThat(projection.pending()).hasSize(1);

        MonthProjection later = MonthProjector.project(context(fixture.input("2026-06-16", "2026-03-01",
                "10000.00")));
        assertThat(later.pending()).isEmpty();
        // 150,00 + 1.000,00 realizados + variável restante 150,00
        assertThat(later.expense().value()).isEqualTo(brl("1300.00"));
        assertThat(later.expense().estimatedPart()).isEqualTo(brl("150.00"));
    }

    @Test
    void ignoredLowConfidenceAndStaleSeriesAreNotPending() {
        RecurringSeries ignored = ForecastFixture.series(SeriesDirection.EXPENSE, "aluguel", 15, "1000.00", "950.00",
                "1050.00", Confidence.HIGH, SeriesStatus.IGNORED);
        ForecastInput input = JuneScenario.fixture().input("2026-06-10", "2026-03-01", "10000.00", List.of(ignored),
                List.of(), List.of());
        MonthProjection projection = MonthProjector.project(context(input));

        assertThat(projection.pending()).isEmpty();
        // a despesa do aluguel, ignorada, passa a ser "variável" (mediana 1.000,00 só dos dias 11 a 30: 1.000 + 150)
        assertThat(projection.expense().estimatedPart()).isEqualTo(brl("1150.00"));
    }

    /** Várias amostras: mediana, [mín, máx], HIGH_VARIABILITY e UNCATEGORIZED. */
    @ParameterizedTest(name = "amostras {0}/{1}/{2} sem categoria {3} → {4}")
    @CsvSource({
            "800.00, 900.00, 1000.00, false, 1000.00, 900.00, 1100.00, MEDIUM, 'SHORT_HISTORY'",
            "600.00, 900.00, 1000.00, false, 1000.00, 700.00, 1100.00, LOW, 'SHORT_HISTORY,HIGH_VARIABILITY'",
            "800.00, 900.00, 1000.00, true,  1000.00, 900.00, 1100.00, LOW, 'SHORT_HISTORY,UNCATEGORIZED'",
    })
    void medianRangeAndConfidenceFromSeveralMonths(String june, String july, String august, boolean uncategorized,
                                                   String value, String low, String high, Confidence confidence,
                                                   String limitations) {
        ForecastFixture fixture = new ForecastFixture()
                .add("2026-06-25", CashFlowRole.EXPENSE, uncategorized ? null : "LEISURE", "loja a", june)
                .add("2026-07-25", CashFlowRole.EXPENSE, "LEISURE", "loja b", july)
                .add("2026-08-25", CashFlowRole.EXPENSE, "LEISURE", "loja c", august)
                .add("2026-09-01", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "100.00");
        MonthProjection projection = MonthProjector.project(context(fixture.input("2026-09-22", "2026-06-01", "0")));

        assertThat(projection.expense().value()).isEqualTo(brl(value));
        assertThat(projection.expense().low()).isEqualTo(brl(low));
        assertThat(projection.expense().high()).isEqualTo(brl(high));
        assertThat(projection.expense().confidence()).isEqualTo(confidence);
        assertThat(projection.expense().limitations()).extracting(Enum::name)
                .containsExactlyElementsOf(List.of(limitations.split(",")));
        assertThat(projection.expense().evidence()).hasSize(3);
    }

    @Test
    void withoutEnoughHistoryOnlyTheFactsAreShown() {
        ForecastFixture fixture = new ForecastFixture()
                .add("2026-08-10", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "200.00")
                .add("2026-09-10", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "300.00")
                .add("2026-09-12", CashFlowRole.INCOME, "SALARY", "empresa", "4000.00");
        MonthProjection projection = MonthProjector.project(context(fixture.input("2026-09-22", "2026-08-10",
                "1000.00")));

        assertThat(projection.baseMonths()).isZero();
        assertThat(projection.expense().basis()).isEqualTo(Basis.FACT);
        assertThat(projection.expense().value()).isEqualTo(brl("300.00"));
        assertThat(projection.expense().partial()).isTrue();
        assertThat(projection.expense().limitations()).containsExactly(Limitation.INSUFFICIENT_HISTORY);
        assertThat(projection.net().value()).isEqualTo(brl("3700.00"));
        assertThat(projection.net().basis()).isEqualTo(Basis.FACT);
    }

    @Test
    void onTheLastDayOfTheMonthThereIsNothingLeftToEstimate() {
        MonthProjection projection = MonthProjector.project(context(JuneScenario.fixture()
                .input("2026-06-30", "2026-03-01", "10000.00")));
        // sem dias restantes: variável 0; o aluguel (15/06) já passou sem ocorrer, e não é pendente
        assertThat(projection.expense().estimatedPart()).isEqualTo(brl("0"));
        assertThat(projection.expense().value()).isEqualTo(brl("150.00"));
        assertThat(projection.pending()).isEmpty();
    }

    /** No último dia de um mês de 30 dias, o gasto do dia 31 dos meses de base não é "restante". */
    @Test
    void daysThatTheCurrentMonthDoesNotHaveAreNotLeftToEstimate() {
        ForecastFixture fixture = new ForecastFixture()
                .add("2026-08-10", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "300.00")
                .add("2026-08-31", CashFlowRole.EXPENSE, "LEISURE", "cinema", "120.00")
                .add("2026-09-10", CashFlowRole.EXPENSE, "GROCERIES", "mercado bom preco", "280.00");
        ForecastInput input = fixture.input("2026-09-30", "2026-08-01", "1000.00");

        MonthProjection projection = MonthProjector.project(context(input), List.of(YearMonth.of(2026, 8)));

        assertThat(projection.expense().estimatedPart()).isEqualTo(brl("0"));
        assertThat(projection.expense().value()).isEqualTo(brl("280.00"));
    }
}
