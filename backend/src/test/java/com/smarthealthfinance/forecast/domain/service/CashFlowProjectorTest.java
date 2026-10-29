package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection.CashFlowMonth;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Fluxo de caixa do mês corrente + 3, lente de caixa (ADR-0030 §3.4). */
class CashFlowProjectorTest {

    private static CashFlowProjection project(ForecastInput input) {
        List<RecurringSeries> detected = RecurrenceDetector.detect(input.entries(), input.today());
        List<RecurringSeries> merged = RecurrenceMerge.merge(input.existingSeries(), detected);
        ProjectionContext ctx = ProjectionContext.of(input,
                SeriesAssessor.assess(merged, detected, input.entries(), input.today()));
        return CashFlowProjector.project(ctx, CardInvoiceProjector.project(ctx,
                CashFlowProjector.horizonEnd(input.today()))).orElseThrow();
    }

    @Test
    void juneScenarioMonthByMonthWithTheRangeAccumulating() {
        CashFlowProjection flow = project(JuneScenario.input());

        assertThat(flow.from()).isEqualTo(date("2026-06-10"));
        assertThat(flow.to()).isEqualTo(date("2026-09-30"));
        assertThat(flow.months()).extracting(m -> m.month().toString())
                .containsExactly("2026-06", "2026-07", "2026-08", "2026-09");

        // junho (depois do dia 10): aluguel 1.000,00 [950–1.050] no 15 e o variável (300,00/mês) no dia 20 (150,00)
        CashFlowMonth june = flow.months().get(0);
        assertThat(june.opening()).isEqualTo(brl("10000.00"));
        assertThat(june.inflowEstimated()).isEqualTo(brl("0"));
        assertThat(june.outflowEstimated()).isEqualTo(brl("1150.00"));
        assertThat(june.closing().value()).isEqualTo(brl("8850.00"));
        assertThat(june.closing().low()).isEqualTo(brl("8800.00"));
        assertThat(june.closing().high()).isEqualTo(brl("8900.00"));
        assertThat(june.closing().factPart()).isEqualTo(brl("10000.00"));
        assertThat(june.closing().estimatedPart()).isEqualTo(brl("-1150.00"));
        assertThat(june.closing().basis()).isEqualTo(Basis.ESTIMATED);

        // julho: entra o salário (5.000,00); a faixa de junho é carregada
        CashFlowMonth july = flow.months().get(1);
        assertThat(july.opening()).isEqualTo(brl("8850.00"));
        assertThat(july.inflowEstimated()).isEqualTo(brl("5000.00"));
        assertThat(july.outflowEstimated()).isEqualTo(brl("1300.00"));
        assertThat(july.closing().value()).isEqualTo(brl("12550.00"));
        assertThat(july.closing().low()).isEqualTo(brl("12450.00"));
        assertThat(july.closing().high()).isEqualTo(brl("12650.00"));

        assertThat(flow.months().get(2).closing().value()).isEqualTo(brl("16250.00"));
        assertThat(flow.months().get(2).closing().low()).isEqualTo(brl("16100.00"));
        assertThat(flow.months().get(2).closing().high()).isEqualTo(brl("16400.00"));
        assertThat(flow.months().get(3).closing().value()).isEqualTo(brl("19950.00"));
        assertThat(flow.months().get(3).closing().low()).isEqualTo(brl("19750.00"));
        assertThat(flow.months().get(3).closing().high()).isEqualTo(brl("20150.00"));

        assertThat(flow.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(flow.limitations()).containsExactly(Limitation.SHORT_HISTORY);
    }

    @Test
    void lowestBalanceAndItsDate() {
        CashFlowProjection flow = project(JuneScenario.input());

        assertThat(flow.trough().lowestCentral().date()).isEqualTo(date("2026-06-20"));
        assertThat(flow.trough().lowestCentral().central()).isEqualTo(brl("8850.00"));
        assertThat(flow.trough().lowestLowBound().date()).isEqualTo(date("2026-06-20"));
        assertThat(flow.trough().lowestLowBound().low()).isEqualTo(brl("8800.00"));
        assertThat(flow.troughNext30().lowestCentral().date()).isEqualTo(date("2026-06-20"));
    }

    @Test
    void theVariableMonthlyMedianIsDistributedByTheAverageDailyCurveAndSumsExactly() {
        // 100,00 em 3 dias de pesos diferentes: dia 5 (50%), dia 12 (30%), dia 28 (20%) em todos os meses de base
        ForecastFixture fixture = new ForecastFixture();
        for (String month : new String[] {"2026-03", "2026-04", "2026-05"}) {
            // sempre o mesmo comerciante, com várias compras por mês: nunca vira série recorrente
            fixture.add(month + "-05", CashFlowRole.EXPENSE, "GROCERIES", "loja a", "50.00")
                    .add(month + "-05", CashFlowRole.EXPENSE, "GROCERIES", "loja a", "0.01")
                    .add(month + "-12", CashFlowRole.EXPENSE, "GROCERIES", "loja a", "29.99")
                    .add(month + "-12", CashFlowRole.EXPENSE, "GROCERIES", "loja a", "0.01")
                    .add(month + "-28", CashFlowRole.EXPENSE, "GROCERIES", "loja a", "10.00")
                    .add(month + "-28", CashFlowRole.EXPENSE, "GROCERIES", "loja a", "9.99");
        }
        CashFlowProjection flow = project(fixture.input("2026-06-01", "2026-03-01", "1000.00"));

        // todo mês de projeção gasta a mediana mensal (100,00 = 50,01 + 30,00 + 19,99), sem sobra nem falta
        CashFlowMonth july = flow.months().get(1);
        assertThat(july.outflowEstimated()).isEqualTo(brl("100.00"));
        assertThat(july.closing().value()).isEqualTo(july.opening().minus(brl("100.00")));
        // junho: de 02/06 em diante inclui os três dias
        assertThat(flow.months().get(0).outflowEstimated()).isEqualTo(brl("100.00"));
    }

    @Test
    void recurringSeriesOnACardAreNotInTheAccountFlow() {
        UUID card = ForecastFixture.AURORA;
        ForecastFixture fixture = new ForecastFixture();
        for (String month : new String[] {"2026-03", "2026-04", "2026-05"}) {
            fixture.add(month + "-15", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00",
                    Channel.card(card), false);
        }
        CashFlowProjection flow = project(fixture.input("2026-06-10", "2026-03-01", "1000.00"));

        // a assinatura está no padrão da fatura, não na conta: o saldo não muda
        assertThat(flow.months()).allSatisfy(m -> assertThat(m.closing().value()).isEqualTo(brl("1000.00")));
    }

    @Test
    void ignoredAndStaleSeriesDoNotMoveTheBalance() {
        RecurringSeries ignored = ForecastFixture.series(SeriesDirection.INCOME, "bonus", 20, "2000.00", "2000.00",
                "2000.00", Confidence.HIGH, SeriesStatus.IGNORED);
        RecurringSeries stale = ForecastFixture.series(SeriesDirection.INCOME, "antigo", 20, "3000.00", "3000.00",
                "3000.00", Confidence.HIGH, SeriesStatus.CONFIRMED);
        ForecastInput base = JuneScenario.input();
        ForecastInput input = new ForecastInput(base.today(), base.currency(), base.firstActivity(), base.entries(),
                List.of(ignored, stale), base.totalBalance(), base.cards(), base.invoiceEvents());

        CashFlowProjection flow = project(input);

        assertThat(flow.months().get(3).closing().value()).isEqualTo(brl("19950.00"));
    }

    @Test
    void cardInvoicesLeaveTheAccountOnTheDueDateFactsAndEstimatesApart() {
        ForecastFixture fixture = new ForecastFixture()
                .addCard("2026-06-24", "GROCERIES", "loja a", "100.00", ForecastFixture.AURORA, false)
                .addCard("2026-07-26", "GROCERIES", "loja b", "80.00", ForecastFixture.AURORA, false)
                .addCard("2026-08-25", "GROCERIES", "loja c", "120.00", ForecastFixture.AURORA, false);
        ForecastInput input = fixture.input("2026-09-22", "2026-03-01", "18420.35", List.of(),
                List.of(ForecastFixture.aurora()), List.of());

        CashFlowProjection flow = project(input);

        assertThat(flow.months()).extracting(m -> m.month().toString())
                .containsExactly("2026-09", "2026-10", "2026-11", "2026-12");
        // setembro: nada sai da conta (a fatura de set vence em 05/out)
        assertThat(flow.months().get(0).closing().value()).isEqualTo(brl("18420.35"));
        // outubro: fatura aberta 3.412,80 (fato) + novas compras ≈ 100,00 [80–120]
        CashFlowMonth october = flow.months().get(1);
        assertThat(october.outflowFact()).isEqualTo(brl("3412.80"));
        assertThat(october.outflowEstimated()).isEqualTo(brl("100.00"));
        assertThat(october.closing().value()).isEqualTo(brl("14907.55"));
        assertThat(october.closing().low()).isEqualTo(brl("14887.55"));
        assertThat(october.closing().high()).isEqualTo(brl("14927.55"));
        // novembro: comprometido 480,00 (fato) + 2.532,10 (estimado)
        CashFlowMonth november = flow.months().get(2);
        assertThat(november.outflowFact()).isEqualTo(brl("480.00"));
        assertThat(november.outflowEstimated()).isEqualTo(brl("2532.10"));
        assertThat(november.closing().value()).isEqualTo(brl("11895.45"));
        assertThat(november.closing().low()).isEqualTo(brl("11697.15"));
        assertThat(november.closing().high()).isEqualTo(brl("11973.45"));
        // dezembro: mesma fatura de novas compras; o fato acumulado separa-se do estimado
        CashFlowMonth december = flow.months().get(3);
        assertThat(december.closing().value()).isEqualTo(brl("8883.35"));
        assertThat(december.closing().factPart()).isEqualTo(brl("14047.55"));
        assertThat(december.closing().estimatedPart()).isEqualTo(brl("-5164.20"));
        assertThat(december.closing().low()).isEqualTo(brl("8506.75"));
        assertThat(december.closing().high()).isEqualTo(brl("9019.35"));

        assertThat(flow.trough().lowestCentral().date()).isEqualTo(date("2026-12-05"));
        assertThat(flow.trough().lowestLowBound().low()).isEqualTo(brl("8506.75"));
        // nos próximos 30 dias (até 22/out) o menor saldo é depois do pagamento de 05/out
        assertThat(flow.troughNext30().lowestCentral().date()).isEqualTo(date("2026-10-05"));
        assertThat(flow.troughNext30().lowestCentral().central()).isEqualTo(brl("14907.55"));
        // a fatura aberta tem amostras de novas compras 80–120 (amplitude 40 > 25% de 100): HIGH_VARIABILITY, no máximo LOW
        assertThat(flow.confidence()).isEqualTo(Confidence.LOW);
        assertThat(flow.limitations()).containsExactly(Limitation.HIGH_VARIABILITY);
    }

    @Test
    void aNegativeLowBoundIsReportedWithItsDate() {
        // saldo baixo: o aluguel (950–1.050) leva o limite de baixo a menos de zero antes do central
        ForecastInput base = JuneScenario.input();
        ForecastInput input = new ForecastInput(base.today(), base.currency(), base.firstActivity(), base.entries(),
                base.existingSeries(), brl("1000.00"), base.cards(), base.invoiceEvents());

        CashFlowProjection flow = project(input);

        assertThat(flow.troughNext30().lowestLowBound().low()).isEqualTo(brl("-200.00"));
        assertThat(flow.troughNext30().lowestLowBound().date()).isEqualTo(date("2026-06-20"));
        assertThat(flow.troughNext30().lowestCentral().central()).isEqualTo(brl("-150.00"));
    }

    @Test
    void withoutEnoughHistoryThereIsNoCashFlow() {
        ForecastFixture fixture = new ForecastFixture().add("2026-08-10", CashFlowRole.EXPENSE, "GROCERIES",
                "mercado", "200.00");
        ForecastInput input = fixture.input("2026-09-22", "2026-08-10", "1000.00");
        List<RecurringSeries> none = List.of();
        ProjectionContext ctx = ProjectionContext.of(input, SeriesAssessor.assess(none, none, input.entries(),
                input.today()));

        assertThat(CashFlowProjector.project(ctx, CardInvoiceProjector.project(ctx,
                CashFlowProjector.horizonEnd(input.today())))).isEmpty();
    }
}
