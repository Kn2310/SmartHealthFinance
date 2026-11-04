package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.OutcomePhase;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome.Figures;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Previsto × realizado: congelamento, reconciliação até o dia 5 e erro (ADR-0030 §6, ADR-0031 §6). */
class MonthOutcomesTest {

    private static final YearMonth JUNE = YearMonth.of(2026, 6);

    private static MonthProjection juneProjection() {
        ForecastInput input = JuneScenario.input();
        List<RecurringSeries> detected = RecurrenceDetector.detect(input.entries(), input.today());
        List<RecurringSeries> merged = RecurrenceMerge.merge(input.existingSeries(), detected);
        return MonthProjector.project(ProjectionContext.of(input,
                SeriesAssessor.assess(merged, detected, input.entries(), input.today())));
    }

    /** Despesa de junho = 1.400,00 (previsto 1.300,00 [1.250–1.350]); receita = 5.000,00. */
    private static ForecastFixture juneActuals() {
        return JuneScenario.fixture()
                .add("2026-06-15", CashFlowRole.EXPENSE, "HOUSING", "aluguel", "1000.00")
                .add("2026-06-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "150.00")
                .add("2026-06-25", CashFlowRole.EXPENSE, "OTHER_EXPENSE", "diversos", "100.00");
    }

    @ParameterizedTest(name = "hoje {0} → {1}")
    @CsvSource({
            "2026-06-15, CURRENT",
            "2026-06-30, CURRENT",
            "2026-07-01, RECONCILING",
            "2026-07-05, RECONCILING",
            "2026-07-06, SETTLED",
            "2026-12-01, SETTLED",
    })
    void phaseOfAMonth(String today, OutcomePhase phase) {
        assertThat(MonthOutcomes.phase(JUNE, date(today))).isEqualTo(phase);
    }

    @ParameterizedTest(name = "último snapshot em {0}, hoje {1} → congela {2}")
    @CsvSource({
            "2026-06-30, 2026-07-01, 2026-06",
            "2026-06-15, 2026-07-02, 2026-06",
            "2026-06-15, 2026-09-10, 2026-06",
            "2026-06-15, 2026-06-16, NONE",
            "2026-06-30, 2026-06-30, NONE",
    })
    void whichMonthToFreeze(String previousAsOf, String today, String month) {
        var result = MonthOutcomes.monthToFreeze(date(previousAsOf), date(today));
        if (month.equals("NONE")) {
            assertThat(result).isEmpty();
        }
        else {
            assertThat(result).contains(YearMonth.parse(month));
        }
    }

    @Test
    void freezeKeepsTheProjectionWithItsRange() {
        MonthOutcome frozen = MonthOutcomes.freeze(juneProjection(), "forecast-v1");

        assertThat(frozen.month()).isEqualTo(JUNE);
        assertThat(frozen.algorithmVersion()).isEqualTo("forecast-v1");
        assertThat(frozen.projected().expense()).isEqualTo(brl("1300.00"));
        assertThat(frozen.projectedLow().expense()).isEqualTo(brl("1250.00"));
        assertThat(frozen.projectedHigh().expense()).isEqualTo(brl("1350.00"));
        assertThat(frozen.projected().income()).isEqualTo(brl("5000.00"));
        assertThat(frozen.projected().net()).isEqualTo(brl("3700.00"));
        assertThat(frozen.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(frozen.realized()).isNull();
        assertThat(frozen.error()).isNull();
    }

    @Test
    void errorIsRealizedMinusProjectedAndPercentOfProjected() {
        MonthOutcome frozen = MonthOutcomes.freeze(juneProjection(), "forecast-v1");
        Figures realized = MonthOutcomes.realized(juneActuals().entries(), JUNE, brl("0"));

        assertThat(realized.expense()).isEqualTo(brl("1400.00"));
        assertThat(realized.income()).isEqualTo(brl("5000.00"));
        assertThat(realized.net()).isEqualTo(brl("3600.00"));

        MonthOutcome outcome = MonthOutcomes.reconcile(frozen, realized);

        assertThat(outcome.error().absolute().expense()).isEqualTo(brl("100.00"));
        assertThat(outcome.error().absolute().income()).isEqualTo(brl("0"));
        assertThat(outcome.error().absolute().net()).isEqualTo(brl("-100.00"));
        // 100 ÷ 1.300 = 7,6923% → 7,69
        assertThat(outcome.error().expensePercent()).isEqualByComparingTo("7.69");
        assertThat(outcome.error().incomePercent()).isEqualByComparingTo("0.00");
        // 1.400,00 ficou acima do máximo 1.350,00; a receita ficou exata
        assertThat(outcome.error().expenseWithinRange()).isFalse();
        assertThat(outcome.error().incomeWithinRange()).isTrue();
    }

    @Test
    void reconcilingAgainWithLateImportsOnlyRewritesTheRealizedAndTheError() {
        MonthOutcome frozen = MonthOutcomes.freeze(juneProjection(), "forecast-v1");
        MonthOutcome first = MonthOutcomes.reconcile(frozen, MonthOutcomes.realized(juneActuals().entries(), JUNE,
                brl("0")));
        ForecastFixture withLate = juneActuals().add("2026-06-30", CashFlowRole.EXPENSE, "OTHER_EXPENSE", "atrasado",
                "50.00");

        MonthOutcome second = MonthOutcomes.reconcile(first, MonthOutcomes.realized(withLate.entries(), JUNE,
                brl("0")));

        // a projeção congelada nunca é reescrita
        assertThat(second.projected()).isEqualTo(first.projected());
        assertThat(second.projectedLow()).isEqualTo(first.projectedLow());
        assertThat(second.projectedHigh()).isEqualTo(first.projectedHigh());
        assertThat(second.confidence()).isEqualTo(first.confidence());
        assertThat(second.realized().expense()).isEqualTo(brl("1450.00"));
        assertThat(second.error().absolute().expense()).isEqualTo(brl("150.00"));
        // idempotente com as mesmas entradas
        assertThat(MonthOutcomes.reconcile(second, MonthOutcomes.realized(withLate.entries(), JUNE, brl("0"))))
                .isEqualTo(second);
    }

    @Test
    void percentIsNullWhenTheProjectionIsZero() {
        assertThat(MonthOutcomes.percent(brl("10.00"), brl("0"))).isNull();
        assertThat(MonthOutcomes.percent(brl("-10.00"), brl("40.00"))).isEqualByComparingTo(new BigDecimal("25.00"));
        // 1 ÷ 8 = 12,5% e 1 ÷ 16 = 6,25%: HALF_EVEN em 2 casas
        assertThat(MonthOutcomes.percent(brl("1"), brl("16"))).isEqualByComparingTo("6.25");
        assertThat(MonthOutcomes.percent(brl("1"), brl("32"))).isEqualByComparingTo("3.12");
    }
}
