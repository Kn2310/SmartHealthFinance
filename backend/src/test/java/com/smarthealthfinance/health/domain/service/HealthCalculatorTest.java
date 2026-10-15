package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.DimensionStatus;
import com.smarthealthfinance.health.domain.enums.FactorDirection;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.HealthState;
import com.smarthealthfinance.health.domain.enums.InsufficientReason;
import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code health-v1} (ADR-0017) com aritmética exata, sobre o fixture de 09-dados-de-exemplo e casos de borda. */
class HealthCalculatorTest {

    private static DimensionResult dimension(HealthResult result, HealthDimension dimension) {
        return result.dimensions().stream().filter(d -> d.dimension() == dimension).findFirst().orElseThrow();
    }

    private static BigDecimal evidence(DimensionResult dimension, String code) {
        return dimension.evidence().stream().filter(e -> e.code().equals(code)).map(Evidence::value).findFirst()
                .orElseThrow();
    }

    @Nested
    class ExampleData {

        private final HealthResult result = HealthCalculator.calculate(HealthFixture.example().asOf("2026-09-22"));

        @Test
        void windowIsTheFiveClosedMonthsBeforeThePartialMonth() {
            assertThat(result.algorithmVersion()).isEqualTo("health-v1");
            assertThat(result.month()).isEqualTo(YearMonth.of(2026, 9));
            assertThat(result.asOf()).isEqualTo(LocalDate.parse("2026-09-22"));
            assertThat(result.partial()).isTrue();
            assertThat(result.windowFrom()).isEqualTo(LocalDate.parse("2026-04-01"));
            assertThat(result.windowTo()).isEqualTo(LocalDate.parse("2026-08-31"));
            assertThat(result.monthsUsed()).isEqualTo(5);
            assertThat(result.estimated()).isFalse();
        }

        /** r = 11.840 ÷ 32.800 = 0,3610 → S = 96,1; p = 5/5 → 0,7 · 96,1 + 30 = 97,27 → 97. */
        @Test
        void cashFlow() {
            DimensionResult cashFlow = dimension(result, HealthDimension.CASH_FLOW);
            assertThat(cashFlow.score()).isEqualTo(97);
            assertThat(cashFlow.attention()).isFalse();
            assertThat(cashFlow.explanation()).isEqualTo("Receitas e reembolsos cobriram as despesas em 5 de 5 meses "
                    + "(abr/2026 a ago/2026). Sobra média de R$ 2.368,00/mês (36,1% da receita).");
            assertThat(evidence(cashFlow, "income")).isEqualByComparingTo("32800.00");
            assertThat(evidence(cashFlow, "expense")).isEqualByComparingTo("20960.00");
            assertThat(evidence(cashFlow, "savingsRate")).isEqualByComparingTo("36.10");
        }

        /**
         * Média 1–22 de abr–ago = 3.482,90; Δ = 363,30 ÷ 3.482,90 = 0,1043 → 73,9967. Excesso: moradia 6,32 +
         * alimentação fora 311,00 + transporte 50,00 + outros 19,90 = 387,22 → E = 0,1112 → 67,0133. Nota 70,505 → 71.
         */
        @Test
        void spending() {
            DimensionResult spending = dimension(result, HealthDimension.SPENDING);
            assertThat(spending.score()).isEqualTo(71);
            assertThat(spending.attention()).isTrue();
            assertThat(evidence(spending, "averageExpense")).isEqualByComparingTo("3482.90");
            assertThat(evidence(spending, "variation")).isEqualByComparingTo("10.43");
            assertThat(evidence(spending, "categoryExcess")).isEqualByComparingTo("11.12");
            assertThat(spending.explanation()).isEqualTo("Gasto +10,4% vs sua média (1 a 22 de set/2026). "
                    + "Alimentação fora está 32,0% acima da média (+R$ 311,00).");
        }

        /** 10.000 ÷ 4.192,00 = 2,39 meses → 45 + 1,39 · 17,5 = 69,325 → 69. */
        @Test
        void reserve() {
            DimensionResult reserve = dimension(result, HealthDimension.RESERVE);
            assertThat(reserve.score()).isEqualTo(69);
            assertThat(reserve.attention()).isTrue();
            assertThat(evidence(reserve, "coverageMonths")).isEqualByComparingTo("2.39");
            assertThat(reserve.explanation()).isEqualTo("Sua reserva cobre ≈ 2,4 meses da média de despesas "
                    + "(R$ 4.192,00/mês, abr/2026 a ago/2026). Referência: 6 meses.");
        }

        /** 5 de 5 meses regulares; 32.500 de 32.800 recorrente = 99,09% → 70 + 29,727 → 100. */
        @Test
        void consistency() {
            DimensionResult consistency = dimension(result, HealthDimension.CONSISTENCY);
            assertThat(consistency.score()).isEqualTo(100);
            assertThat(evidence(consistency, "recurringIncomeShare")).isEqualByComparingTo("99.09");
            assertThat(consistency.explanation()).isEqualTo("Receitas e despesas ficaram dentro do seu padrão em 5 "
                    + "de 5 meses; 99,1% da receita vem de fontes recorrentes.");
        }

        @Test
        void creditAndDebtAreNotApplicable() {
            for (HealthDimension code : List.of(HealthDimension.CREDIT, HealthDimension.DEBT)) {
                DimensionResult dimension = dimension(result, code);
                assertThat(dimension.status()).isEqualTo(DimensionStatus.NOT_APPLICABLE);
                assertThat(dimension.score()).isNull();
                assertThat(dimension.explanation()).isEqualTo("Disponível quando houver cartões e parcelamentos.");
            }
        }

        /** (25·97 + 15·71 + 20·69 + 10·100) ÷ 70 = 5.870 ÷ 70 = 83,86 → 84. */
        @Test
        void overallScoreRenormalizesOverTheAvailableWeight() {
            assertThat(result.state()).isEqualTo(HealthState.READY);
            assertThat(result.score()).isEqualTo(84);
            assertThat(result.coverage()).isEqualTo(new HealthResult.Coverage(4, 6, 70));
            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(result.uncategorizedShare()).isEqualByComparingTo("0");
            assertThat(result.summary()).isEqualTo("Seu resultado combina 4 de 6 dimensões. Consistência (100) e "
                    + "fluxo de caixa (97) sustentam o score; reserva (69) é o ponto que mais pesa.");
        }

        @Test
        void factorsAreOrderedByWeightTimesDistanceFromSeventy() {
            assertThat(result.factors()).extracting(f -> f.direction() + " " + f.dimension()).containsExactly(
                    "SUSTAINING CASH_FLOW", "SUSTAINING CONSISTENCY", "WEIGHING RESERVE", "WEIGHING SPENDING");
            assertThat(result.factors().get(2).text()).isEqualTo(dimension(result, HealthDimension.RESERVE)
                    .explanation());
        }

        @Test
        void nextStepsPointOnlyToExistingScreens() {
            assertThat(result.nextSteps()).extracting(s -> s.kind()).containsExactly(NextStepKind.BUILD_RESERVE,
                    NextStepKind.REVIEW_CATEGORY);
            assertThat(result.nextSteps().getFirst().evidence()).isEqualTo("Faltam R$ 15.152,00 para 6 meses de "
                    + "despesas.");
            assertThat(result.nextSteps().getFirst().amount().amount()).isEqualByComparingTo("15152.00");
            assertThat(result.nextSteps().get(1).text()).isEqualTo("Alimentação fora 32,0% acima da média");
            assertThat(result.nextSteps().get(1).categoryCode()).isEqualTo("DINING_OUT");
            assertThat(result.nextSteps().get(1).month()).isEqualTo(YearMonth.of(2026, 9));
        }

        @Test
        void variationComparesWithThePreviousMonthOfTheSameVersion() {
            HealthResult august = HealthCalculator.calculate(HealthFixture.example().closed("2026-08", "2026-09-22"));
            assertThat(august.partial()).isFalse();
            assertThat(august.windowTo()).isEqualTo(LocalDate.parse("2026-08-31"));

            HealthResult withVariation = HealthCalculator.withVariation(result, august);
            assertThat(withVariation.variation()).isEqualTo(result.score() - august.score());
            assertThat(HealthCalculator.withVariation(result, null).variation()).isNull();
        }
    }

    @Nested
    class Thresholds {

        @Test
        void withoutIncomeButWithExpensesTheRateScoresZero() {
            HealthFixture fixture = new HealthFixture()
                    .add("2026-08-10", CashFlowRole.EXPENSE, "HOUSING", null, "1000.00")
                    .reserve("500.00");
            DimensionResult cashFlow = dimension(HealthCalculator.calculate(fixture.asOf("2026-09-10")),
                    HealthDimension.CASH_FLOW);
            // S(r) = 0 e p = 0/1 → 0.
            assertThat(cashFlow.score()).isZero();
            assertThat(cashFlow.attention()).isTrue();
            assertThat(cashFlow.explanation()).endsWith("Não houve receitas no período.");
        }

        @Test
        void withoutClosedMonthsThereIsNoScore() {
            HealthFixture fixture = new HealthFixture()
                    .add("2026-09-05", CashFlowRole.INCOME, "SALARY", "empresa", "5000.00")
                    .reserve("1000.00");
            HealthResult result = HealthCalculator.calculate(fixture.asOf("2026-09-10"));
            assertThat(result.state()).isEqualTo(HealthState.INSUFFICIENT_DATA);
            assertThat(result.score()).isNull();
            assertThat(result.summary()).isNull();
            assertThat(dimension(result, HealthDimension.CASH_FLOW).reason())
                    .isEqualTo(InsufficientReason.NO_CLOSED_MONTH);
            assertThat(dimension(result, HealthDimension.SPENDING).reason()).isEqualTo(InsufficientReason.NO_AVERAGE);
            assertThat(result.confidence()).isEqualTo(Confidence.LOW);
            assertThat(result.estimated()).isTrue();
            assertThat(result.nextSteps()).isEmpty();
        }

        @Test
        void oneClosedMonthGivesAnEstimatedScoreWithALimitedAverage() {
            HealthFixture fixture = new HealthFixture()
                    .add("2026-08-05", CashFlowRole.INCOME, "SALARY", "empresa", "5000.00")
                    .add("2026-08-06", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "2000.00")
                    .add("2026-09-05", CashFlowRole.INCOME, "SALARY", "empresa", "5000.00")
                    .add("2026-09-06", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "2000.00")
                    .noReserves();
            HealthResult result = HealthCalculator.calculate(fixture.asOf("2026-09-10"));

            assertThat(result.state()).isEqualTo(HealthState.READY);
            assertThat(result.estimated()).isTrue();
            assertThat(result.monthsUsed()).isEqualTo(1);
            assertThat(dimension(result, HealthDimension.SPENDING).status()).isEqualTo(DimensionStatus.OK);
            assertThat(dimension(result, HealthDimension.RESERVE).reason())
                    .isEqualTo(InsufficientReason.NO_RESERVE_ACCOUNT);
            assertThat(dimension(result, HealthDimension.CONSISTENCY).reason())
                    .isEqualTo(InsufficientReason.NEEDS_THREE_CLOSED_MONTHS);
            assertThat(result.coverage()).isEqualTo(new HealthResult.Coverage(2, 6, 40));
            assertThat(result.nextSteps()).extracting(s -> s.kind())
                    .containsExactly(NextStepKind.ADD_RESERVE_ACCOUNT);
        }

        @Test
        void cashFlowAloneIsNotEnoughForAnOverallScore() {
            HealthFixture fixture = new HealthFixture()
                    .add("2026-08-05", CashFlowRole.INCOME, "SALARY", "empresa", "5000.00")
                    .noReserves();
            HealthResult result = HealthCalculator.calculate(fixture.asOf("2026-09-10"));
            assertThat(dimension(result, HealthDimension.CASH_FLOW).isOk()).isTrue();
            assertThat(dimension(result, HealthDimension.SPENDING).reason())
                    .isEqualTo(InsufficientReason.ZERO_AVERAGE);
            assertThat(result.state()).isEqualTo(HealthState.INSUFFICIENT_DATA);
        }

        @Test
        void negativeReserveScoresZeroAndNegativeMonthsSuggestReviewingTheMonth() {
            HealthFixture fixture = new HealthFixture()
                    .add("2026-07-05", CashFlowRole.INCOME, "SALARY", "empresa", "3000.00")
                    .add("2026-07-06", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "2000.00")
                    .add("2026-08-05", CashFlowRole.INCOME, "SALARY", "empresa", "3000.00")
                    .add("2026-08-06", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "3500.00")
                    .add("2026-09-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "100.00")
                    .reserve("-50.00");
            HealthResult result = HealthCalculator.calculate(fixture.asOf("2026-09-10"));

            DimensionResult reserve = dimension(result, HealthDimension.RESERVE);
            assertThat(reserve.score()).isZero();
            assertThat(evidence(reserve, "coverageMonths")).isEqualByComparingTo("0");
            DimensionResult cashFlow = dimension(result, HealthDimension.CASH_FLOW);
            assertThat(cashFlow.explanation()).contains("1 de 2 meses").endsWith(
                    "Sobra média de R$ 250,00/mês (8,3% da receita).");
            assertThat(cashFlow.attention()).isTrue();
            assertThat(result.nextSteps()).extracting(s -> s.kind()).containsExactly(NextStepKind.BUILD_RESERVE,
                    NextStepKind.REVIEW_MONTH);
            assertThat(result.nextSteps().getLast().text()).isEqualTo("Veja o que mudou em ago/2026");
            assertThat(result.nextSteps().getLast().evidence()).isEqualTo("Resultado de -R$ 500,00 no mês.");
        }

        @Test
        void deficitIsWrittenAsADeficit() {
            HealthFixture fixture = new HealthFixture()
                    .add("2026-08-05", CashFlowRole.INCOME, "SALARY", "empresa", "1000.00")
                    .add("2026-08-06", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1200.00")
                    .noReserves();
            DimensionResult cashFlow = dimension(HealthCalculator.calculate(fixture.asOf("2026-09-10")),
                    HealthDimension.CASH_FLOW);
            // r = −0,2 → S = 0; p = 0 → 0.
            assertThat(cashFlow.score()).isZero();
            assertThat(cashFlow.explanation()).endsWith("Déficit médio de R$ 200,00/mês (-20,0% da receita).");
        }

        @Test
        void uncategorizedSpendingAboveTenPercentSuggestsCategorizing() {
            HealthFixture fixture = HealthFixture.example()
                    .add("2026-08-25", CashFlowRole.EXPENSE, null, null, "3000.00");
            HealthResult result = HealthCalculator.calculate(fixture.asOf("2026-09-22"));
            // 3.000 ÷ 23.960 = 12,5% sem categoria.
            assertThat(result.uncategorizedShare()).isEqualByComparingTo("12.5");
            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(result.nextSteps()).extracting(s -> s.kind()).contains(NextStepKind.CATEGORIZE_TRANSACTIONS);
        }
    }

    @Nested
    class Rules {

        @ParameterizedTest
        @CsvSource({
                "6, 10.0, HIGH",
                "6, 10.1, MEDIUM",
                "5, 0.0,  MEDIUM",
                "3, 25.0, MEDIUM",
                "3, 25.1, LOW",
                "2, 0.0,  LOW",
                "0, 0.0,  LOW",
        })
        void confidence(int months, String uncategorized, Confidence expected) {
            assertThat(HealthCalculator.confidence(months, new BigDecimal(uncategorized))).isEqualTo(expected);
        }

        /** Arredondamento direto para inteiro: 85,4999… nunca vira 86 por arredondar duas vezes. */
        @Test
        void overallRoundsOnceHalfEven() {
            List<DimensionResult> available = List.of(
                    DimensionResult.ok(HealthDimension.CASH_FLOW, 85, "x", false, List.of()),
                    DimensionResult.ok(HealthDimension.RESERVE, 86, "x", false, List.of()),
                    DimensionResult.ok(HealthDimension.SPENDING, 85, "x", false, List.of()));
            // (25·85 + 20·86 + 15·85) ÷ 60 = 5.120 ÷ 60 = 85,333… → 85
            assertThat(HealthCalculator.overall(available, 60)).isEqualTo(85);
            List<DimensionResult> half = List.of(
                    DimensionResult.ok(HealthDimension.CASH_FLOW, 70, "x", false, List.of()),
                    DimensionResult.ok(HealthDimension.CONSISTENCY, 71, "x", false, List.of()),
                    DimensionResult.ok(HealthDimension.SPENDING, 70, "x", false, List.of()),
                    DimensionResult.ok(HealthDimension.DEBT, 71, "x", false, List.of()));
            // (25·70 + 10·71 + 15·70 + 15·71) ÷ 65 = 4.575 ÷ 65 = 70,3846 → 70
            assertThat(HealthCalculator.overall(half, 65)).isEqualTo(70);
        }

        @Test
        void medianOfAnEvenCountIsTheMeanOfTheMiddleValues() {
            assertThat(ConsistencyDimension.median(List.of(new BigDecimal("4"), new BigDecimal("1"),
                    new BigDecimal("3"), new BigDecimal("2")))).isEqualByComparingTo("2.5");
        }

        @Test
        void summaryWithoutHighlights() {
            List<DimensionResult> available = List.of(
                    DimensionResult.ok(HealthDimension.CASH_FLOW, 75, "x", false, List.of()),
                    DimensionResult.ok(HealthDimension.RESERVE, 72, "x", false, List.of()));
            assertThat(HealthNarrative.summary(available, new HealthResult.Coverage(2, 6, 45))).isEqualTo(
                    "Seu resultado combina 2 de 6 dimensões. Nenhuma dimensão se destaca: todas ficaram entre 70 e "
                            + "79.");
            assertThat(HealthNarrative.factors(available)).isEmpty();
        }

        @Test
        void aDimensionWithAttentionWeighsEvenWithAHighScore() {
            List<DimensionResult> available = List.of(
                    DimensionResult.ok(HealthDimension.SPENDING, 85, "x", true, List.of()));
            assertThat(HealthNarrative.factors(available)).singleElement()
                    .extracting(f -> f.direction()).isEqualTo(FactorDirection.WEIGHING);
        }
    }
}
