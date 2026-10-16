package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Catálogo {@code insights-v1} (ADR-0021 §2): cada detector com os limiares exatos e os casos de borda. */
class InsightEngineTest {

    private static List<InsightCandidate> of(List<InsightCandidate> all, InsightDetector detector) {
        return all.stream().filter(c -> c.detector() == detector).toList();
    }

    private static InsightCandidate only(List<InsightCandidate> all, InsightDetector detector) {
        List<InsightCandidate> found = of(all, detector);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static BigDecimal figure(InsightCandidate candidate, String code) {
        return candidate.content().evidence().figures().stream().filter(f -> f.code().equals(code))
                .map(KeyFigure::value).findFirst().orElseThrow();
    }

    private static InsightAction primary(InsightCandidate candidate) {
        return candidate.content().actions().stream().filter(InsightAction::primary).findFirst().orElseThrow();
    }

    /**
     * Abr–ago com salário de 5.000 e moradia de {@code housing} no dia 5, e {@code base} de alimentação fora no dia
     * 10; setembro (visto em 20/09) com o mesmo salário e moradia e {@code september} de alimentação fora.
     */
    private static InsightFixture dining(String housing, String base, String september) {
        InsightFixture fixture = new InsightFixture().withoutHealth();
        for (String month : List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09")) {
            fixture.add(month + "-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "5000.00");
            fixture.add(month + "-05", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", housing);
            fixture.add(month + "-10", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja",
                    month.equals("2026-09") ? september : base);
        }
        return fixture;
    }

    @Nested
    class ExampleData {

        private final List<InsightCandidate> all = InsightFixture.example().detect("2026-09-22");

        @Test
        void detectsTheCategorySpikeTheHealthChangesAndTheLowReserve() {
            assertThat(all).extracting(InsightCandidate::detector, InsightCandidate::subject).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(InsightDetector.CATEGORY_SPIKE, "DINING_OUT"),
                    org.assertj.core.groups.Tuple.tuple(InsightDetector.HEALTH_SCORE_CHANGE, "SCORE"),
                    org.assertj.core.groups.Tuple.tuple(InsightDetector.HEALTH_DIMENSION_CHANGE, "SPENDING"),
                    org.assertj.core.groups.Tuple.tuple(InsightDetector.LOW_RESERVE, "2-3"));
        }

        /** 1.284,00 (1–22/09) contra 973,00 (média abr–ago, 1–22): +311,00, 31,96% → 32%; R = 4.192,00. */
        @Test
        void categorySpikeHasExactNumbersAndTexts() {
            InsightCandidate spike = only(all, InsightDetector.CATEGORY_SPIKE);
            assertThat(spike.periodKey()).isEqualTo("2026-09");
            assertThat(spike.impactAmount().amount()).isEqualByComparingTo("311.00");
            assertThat(spike.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(spike.partial()).isTrue();
            assertThat(spike.content().title()).isEqualTo(
                    "Seus gastos com alimentação fora aumentaram 32% neste mês, cerca de R$ 310 a mais.");
            assertThat(spike.content().support()).isEqualTo("R$ 1.284,00 até 22 de setembro, contra média de "
                    + "R$ 973,00 no mesmo período de abr–ago. ENTREGA JA concentra 35% do valor.");
            assertThat(spike.content().recommendation().title()).isEqualTo("Revisar ENTREGA JA");
            assertThat(spike.content().recommendation().text())
                    .isEqualTo("ENTREGA JA concentra 35% do gasto da categoria (R$ 447,00 em 1 compra).");
            assertThat(spike.content().evidence().comparison()).isEqualTo("Comparamos o que você gastou em "
                    + "alimentação fora entre 1 e 22 de setembro com a média do mesmo período nos 5 meses anteriores "
                    + "(abr–ago).");
            assertThat(figure(spike, "current")).isEqualByComparingTo("1284.00");
            assertThat(figure(spike, "average")).isEqualByComparingTo("973.00");
            assertThat(figure(spike, "difference")).isEqualByComparingTo("311.00");
            assertThat(figure(spike, "percent")).isEqualByComparingTo("32.0");
            assertThat(spike.content().evidence().series()).hasSize(6);
            assertThat(spike.content().evidence().series().getLast().partial()).isTrue();
            assertThat(spike.content().evidence().contributors()).extracting(Contributor::name)
                    .containsExactly("ENTREGA JA", "BISTRO LUME", "PIZZARIA FORNO", "CAFE GRAO");
            assertThat(spike.content().evidence().limitations()).extracting(Limitation::code)
                    .containsExactly(LimitationCode.PARTIAL_MONTH);

            assertThat(primary(spike).kind()).isEqualTo(ActionKind.VIEW_TRANSACTIONS);
            assertThat(primary(spike).label()).isEqualTo("Ver as 4 transações");
            assertThat(primary(spike).params()).isEqualTo(Map.of("from", "2026-09-01", "to", "2026-09-22",
                    "type", "EXPENSE", "category", "DINING_OUT"));
            assertThat(spike.content().actions()).filteredOn(a -> !a.available()).extracting(InsightAction::kind)
                    .containsExactly(ActionKind.CREATE_SAVINGS_GOAL, ActionKind.ASK_COPILOT);
            assertThat(spike.content().refs().categoryCode()).isEqualTo("DINING_OUT");
            assertThat(spike.content().refs().dimension()).isEqualTo(HealthDimension.SPENDING);
        }

        @Test
        void healthChangesComeFromTheSnapshot() {
            InsightCandidate score = only(all, InsightDetector.HEALTH_SCORE_CHANGE);
            assertThat(score.content().title()).isEqualTo("Seu score caiu 6 pontos em relação a agosto.");
            assertThat(score.impactPoints()).isEqualTo(6);
            assertThat(score.importantByRule()).isFalse();

            InsightCandidate dimension = only(all, InsightDetector.HEALTH_DIMENSION_CHANGE);
            assertThat(dimension.content().title())
                    .isEqualTo("A nota de gastos passou de 100 para 71 em relação a agosto.");
            assertThat(dimension.impactPoints()).isEqualTo(29);
            assertThat(dimension.content().refs().dimension()).isEqualTo(HealthDimension.SPENDING);
        }

        /** 10.000 ÷ 4.192 = 2,39 meses; faltam 6 × 4.192 − 10.000 = 15.152,00. */
        @Test
        void lowReserve() {
            InsightCandidate reserve = only(all, InsightDetector.LOW_RESERVE);
            assertThat(reserve.periodKey()).isNull();
            assertThat(reserve.subject()).isEqualTo("2-3");
            assertThat(reserve.importantByRule()).isFalse();
            assertThat(reserve.impactAmount().amount()).isEqualByComparingTo("15152.00");
            assertThat(reserve.content().title()).isEqualTo("Sua reserva cobre cerca de 2,4 meses de despesas.");
            assertThat(reserve.content().support()).isEqualTo("A referência usada no score é 6 meses. Faltam "
                    + "R$ 15.152,00 (média de R$ 4.192,00/mês em abr–ago).");
            assertThat(primary(reserve).kind()).isEqualTo(ActionKind.VIEW_HEALTH);
        }

        /** Do dia 1 ao 6 de outubro, P é setembro fechado: meses cheios na comparação e "em setembro". */
        @Test
        void closedMonthIsEvaluatedUntilTheSixthDay() {
            InsightCandidate spike = only(InsightFixture.example().detect("2026-10-03"),
                    InsightDetector.CATEGORY_SPIKE);
            assertThat(spike.periodKey()).isEqualTo("2026-09");
            assertThat(spike.partial()).isFalse();
            assertThat(spike.content().title()).isEqualTo(
                    "Seus gastos com alimentação fora aumentaram 32% em setembro, cerca de R$ 310 a mais.");
            assertThat(spike.content().support()).startsWith(
                    "R$ 1.284,00 em setembro, contra média de R$ 973,00 em abr–ago.");
            assertThat(spike.content().evidence().limitations()).isEmpty();
        }

        @Test
        void sameInputSameCandidates() {
            assertThat(InsightFixture.example().detect("2026-09-22")).isEqualTo(all);
        }
    }

    @Nested
    class CategorySpike {

        @ParameterizedTest(name = "moradia {0}, base {1}, setembro {2} → {3}")
        @CsvSource({
                // Δ = 100, 20%, Δ ÷ R = 100 ÷ 2.500 = 4%: limiares exatos
                "2000.00, 500.00, 600.00, true",
                // Δ 99,99 abaixo do piso de R$ 100
                "2000.00, 500.00, 599.99, false",
                // +19,99%
                "2000.00, 1000.50, 1200.50, false",
                // Δ ÷ R = 100 ÷ 5.100 = 1,96% < 3%
                "5000.00, 100.00, 200.00, false",
                // "R$ 12,50 a mais em café": nunca passa
                "2000.00, 50.00, 62.50, false"})
        void thresholds(String housing, String base, String september, boolean fires) {
            List<InsightCandidate> found = of(dining(housing, base, september).detect("2026-09-20"),
                    InsightDetector.CATEGORY_SPIKE);
            assertThat(found).hasSize(fires ? 1 : 0);
        }

        @Test
        void needsTwoMonthsInTheAverage() {
            InsightFixture fixture = new InsightFixture().withoutHealth()
                    .add("2026-08-10", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "500.00")
                    .add("2026-09-10", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "1500.00");
            assertThat(of(fixture.detect("2026-09-20"), InsightDetector.CATEGORY_SPIKE)).isEmpty();

            fixture.add("2026-07-10", CashFlowRole.EXPENSE, "DINING_OUT", "entrega ja", "500.00");
            InsightCandidate spike = only(fixture.detect("2026-09-20"), InsightDetector.CATEGORY_SPIKE);
            assertThat(spike.confidence()).isEqualTo(Confidence.LOW);
            assertThat(spike.content().evidence().limitations()).extracting(Limitation::code)
                    .containsExactly(LimitationCode.PARTIAL_MONTH, LimitationCode.SHORT_HISTORY);
        }

        @Test
        void keepsTheThreeLargestAndNeverTheUncategorized() {
            InsightFixture fixture = new InsightFixture().withoutHealth();
            String[] categories = {"DINING_OUT", "GROCERIES", "TRANSPORT", "LEISURE"};
            for (String month : List.of("2026-05", "2026-06", "2026-07", "2026-08")) {
                for (String category : categories) {
                    fixture.add(month + "-10", CashFlowRole.EXPENSE, category, null, "500.00");
                }
                fixture.add(month + "-10", CashFlowRole.EXPENSE, null, null, "500.00");
            }
            String[] september = {"900.00", "800.00", "700.00", "1000.00"};
            for (int i = 0; i < categories.length; i++) {
                fixture.add("2026-09-10", CashFlowRole.EXPENSE, categories[i], null, september[i]);
            }
            fixture.add("2026-09-10", CashFlowRole.EXPENSE, null, null, "2000.00");
            assertThat(of(fixture.detect("2026-09-20"), InsightDetector.CATEGORY_SPIKE))
                    .extracting(InsightCandidate::subject).containsExactly("DINING_OUT", "GROCERIES", "LEISURE");
        }

        @Test
        void manyUncategorizedLowerTheConfidence() {
            InsightFixture fixture = dining("2000.00", "500.00", "900.00");
            for (String month : List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09")) {
                fixture.add(month + "-06", CashFlowRole.EXPENSE, null, null, "1500.00");
            }
            assertThat(only(fixture.detect("2026-09-20"), InsightDetector.CATEGORY_SPIKE).confidence())
                    .isEqualTo(Confidence.LOW);
        }
    }

    @Nested
    class SpendingAbovePattern {

        /** Base 2.500 (2.000 + 500); setembro 2.000 + 900 = 2.900: +400, +16% → dispara; com +300 (12%) não. */
        @Test
        void totalAboveFifteenPercentAndTwoHundred() {
            InsightCandidate total = only(dining("2000.00", "500.00", "900.00").detect("2026-09-20"),
                    InsightDetector.SPENDING_ABOVE_PATTERN);
            assertThat(total.content().title())
                    .isEqualTo("Seus gastos estão 16% acima do seu padrão neste mês, cerca de R$ 400 a mais.");
            assertThat(total.content().support()).isEqualTo("A maior alta foi em alimentação fora (+R$ 400,00).");
            assertThat(primary(total).kind()).isEqualTo(ActionKind.VIEW_ANALYSIS);
            assertThat(primary(total).params()).isEqualTo(Map.of("month", "2026-09"));

            assertThat(of(dining("2000.00", "500.00", "800.00").detect("2026-09-20"),
                    InsightDetector.SPENDING_ABOVE_PATTERN)).isEmpty();
        }
    }

    @Nested
    class NewRecurringMerchant {

        private InsightFixture subscription(String firstCharge, String... amounts) {
            InsightFixture fixture = new InsightFixture().withoutHealth()
                    .add("2026-02-01", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "5000.00");
            String[] months = {"2026-06", "2026-07", "2026-08", "2026-09"};
            int start = List.of(months).indexOf(firstCharge);
            for (int i = start; i < months.length; i++) {
                String amount = amounts.length == 0 ? "39.90" : amounts[i - start];
                fixture.add(months[i] + "-03", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo novo", amount);
            }
            return fixture;
        }

        @Test
        void threeOrMoreMonthlyChargesAfterThreeMonthsWithout() {
            InsightCandidate merchant = only(subscription("2026-06").detect("2026-09-20"),
                    InsightDetector.NEW_RECURRING_MERCHANT);
            assertThat(merchant.periodKey()).isEqualTo("2026-06");
            assertThat(merchant.subject()).isEqualTo("streamo novo");
            assertThat(merchant.content().title()).isEqualTo("STREAMO NOVO cobra cerca de R$ 40 todo mês desde junho.");
            assertThat(merchant.content().support())
                    .isEqualTo("Pode ser uma assinatura nova: 4 cobranças de R$ 39,90, perto do dia 3.");
            assertThat(merchant.content().refs().categoryCode()).isEqualTo("SUBSCRIPTIONS");
            assertThat(primary(merchant).kind()).isEqualTo(ActionKind.VIEW_MERCHANTS);
        }

        @Test
        void partialMonthWithoutTheChargeYetLooksAtTheThreeBefore() {
            InsightFixture fixture = new InsightFixture().withoutHealth()
                    .add("2026-02-01", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "5000.00");
            for (String month : List.of("2026-06", "2026-07", "2026-08")) {
                fixture.add(month + "-25", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo novo", "39.90");
            }
            assertThat(of(fixture.detect("2026-09-20"), InsightDetector.NEW_RECURRING_MERCHANT)).hasSize(1);
        }

        @Test
        void notNewNotRegularOrWithoutHistory() {
            // Começou em maio: já tem 5 meses, não é novo.
            InsightFixture old = subscription("2026-06");
            old.add("2026-05-03", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo novo", "39.90");
            assertThat(of(old.detect("2026-09-20"), InsightDetector.NEW_RECURRING_MERCHANT)).isEmpty();
            // Uma cobrança 13% acima da mediana.
            assertThat(of(subscription("2026-06", "39.90", "39.90", "45.10", "39.90").detect("2026-09-20"),
                    InsightDetector.NEW_RECURRING_MERCHANT)).isEmpty();
            // Histórico não cobre os 3 meses antes da série.
            InsightFixture recent = new InsightFixture().withoutHealth();
            for (String month : List.of("2026-06", "2026-07", "2026-08", "2026-09")) {
                recent.add(month + "-03", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo novo", "39.90");
            }
            assertThat(of(recent.detect("2026-09-20"), InsightDetector.NEW_RECURRING_MERCHANT)).isEmpty();
        }
    }

    @Nested
    class UnusualExpense {

        private final UUID purchase = UUID.fromString("00000000-0000-7000-8000-000000000001");

        private InsightFixture shopping(int purchases, String amount, String category) {
            InsightFixture fixture = new InsightFixture().withoutHealth();
            String[] months = {"2026-04", "2026-05", "2026-06", "2026-07", "2026-08"};
            for (int i = 0; i < purchases; i++) {
                fixture.add(months[i % months.length] + "-08", CashFlowRole.EXPENSE, "SHOPPING", "loja", "100.00");
            }
            return fixture.expense(purchase, "2026-09-15", category, "loja x", amount);
        }

        @Test
        void threeTimesTheAverageTicketAndAtLeastTwoHundredFifty() {
            InsightCandidate unusual = only(shopping(5, "400.00", "SHOPPING").detect("2026-09-20"),
                    InsightDetector.UNUSUAL_EXPENSE);
            assertThat(unusual.subject()).isEqualTo(purchase.toString());
            assertThat(unusual.impactAmount().amount()).isEqualByComparingTo("300.00");
            assertThat(unusual.content().title())
                    .isEqualTo("Uma compra de R$ 400,00 em compras ficou 4,0 vezes acima do seu valor médio.");
            assertThat(unusual.content().support())
                    .isEqualTo("LOJA X em 15 set. Seu valor médio em compras é R$ 100,00 (5 compras em abr–ago).");
            assertThat(unusual.content().refs().transactionId()).isEqualTo(purchase);
            assertThat(primary(unusual).params()).isEqualTo(Map.of("transactionId", purchase.toString()));
            // A descrição livre da transação nunca entra no texto.
            assertThat(unusual.content().title() + unusual.content().support()).doesNotContain("descricao livre");
        }

        @ParameterizedTest(name = "{0} compras, {1} em {2}")
        @CsvSource({"5, 299.99, SHOPPING", "4, 400.00, SHOPPING", "5, 249.99, SHOPPING"})
        void belowTheThresholds(int purchases, String amount, String category) {
            assertThat(of(shopping(purchases, amount, category).detect("2026-09-20"),
                    InsightDetector.UNUSUAL_EXPENSE)).isEmpty();
        }

        @Test
        void neverForUncategorized() {
            InsightFixture fixture = shopping(5, "400.00", "SHOPPING");
            fixture.expense(UUID.randomUUID(), "2026-09-16", null, "loja y", "900.00");
            assertThat(of(fixture.detect("2026-09-20"), InsightDetector.UNUSUAL_EXPENSE)).hasSize(1);
        }
    }

    @Nested
    class RecurringIncome {

        private InsightFixture salary(String september) {
            InsightFixture fixture = new InsightFixture().withoutHealth();
            for (String month : List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08")) {
                fixture.add(month + "-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "5000.00");
            }
            if (september != null) {
                fixture.add("2026-09-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", september);
            }
            return fixture;
        }

        @Test
        void missingOnlyAfterTheTypicalDayPlusFive() {
            assertThat(of(salary(null).detect("2026-09-10"), InsightDetector.RECURRING_INCOME_MISSING)).isEmpty();

            InsightCandidate missing = only(salary(null).detect("2026-09-11"),
                    InsightDetector.RECURRING_INCOME_MISSING);
            assertThat(missing.subject()).isEqualTo("merchant:empresa ficticia");
            assertThat(missing.impactAmount().amount()).isEqualByComparingTo("5000.00");
            assertThat(missing.content().title()).isEqualTo("Ainda não identificamos EMPRESA FICTICIA neste mês.");
            assertThat(missing.content().support()).isEqualTo("Ela costuma entrar por volta do dia 5, em média "
                    + "R$ 5.000,00. Se já recebeu, importe o extrato mais recente.");
            assertThat(missing.content().evidence().limitations()).extracting(Limitation::code)
                    .containsExactly(LimitationCode.PARTIAL_MONTH, LimitationCode.IMPORT_LAG);
            assertThat(primary(missing).kind()).isEqualTo(ActionKind.IMPORT_STATEMENT);
        }

        @Test
        void closedMonthWithoutTheIncome() {
            InsightCandidate missing = only(salary(null).detect("2026-10-02"),
                    InsightDetector.RECURRING_INCOME_MISSING);
            assertThat(missing.content().title()).isEqualTo("Não identificamos EMPRESA FICTICIA em setembro.");
        }

        @Test
        void lowerBelowEightyPercentAndTwoHundred() {
            InsightCandidate lower = only(salary("3000.00").detect("2026-09-20"),
                    InsightDetector.RECURRING_INCOME_LOWER);
            assertThat(lower.impactAmount().amount()).isEqualByComparingTo("2000.00");
            assertThat(lower.content().title())
                    .isEqualTo("EMPRESA FICTICIA veio cerca de R$ 2.000 abaixo da média neste mês.");
            assertThat(lower.content().support()).isEqualTo("R$ 3.000,00 até agora, contra média de R$ 5.000,00.");
            assertThat(primary(lower).kind()).isEqualTo(ActionKind.VIEW_INCOME);

            assertThat(of(salary("4000.00").detect("2026-09-20"), InsightDetector.RECURRING_INCOME_LOWER)).isEmpty();
        }
    }

    @Nested
    class NewIncomeSource {

        @Test
        void sourceAbsentInTheSixMonthsBefore() {
            InsightFixture fixture = new InsightFixture().withoutHealth();
            for (String month : List.of("2026-05", "2026-06", "2026-07", "2026-08", "2026-09")) {
                fixture.add(month + "-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "5000.00");
            }
            fixture.add("2026-09-18", CashFlowRole.INCOME, "SELF_EMPLOYED", "cliente novo", "1200.00");
            InsightCandidate source = only(fixture.detect("2026-09-20"), InsightDetector.NEW_INCOME_SOURCE);
            assertThat(source.content().title()).isEqualTo("Você recebeu R$ 1.200,00 de uma nova fonte: CLIENTE NOVO.");
            assertThat(source.content().support())
                    .isEqualTo("Entrada em 18 set. Nenhuma receita dessa fonte nos 6 meses anteriores.");
        }

        @Test
        void needsThreeClosedMonthsOfHistory() {
            InsightFixture fixture = new InsightFixture().withoutHealth()
                    .add("2026-07-05", CashFlowRole.INCOME, "SALARY", "empresa ficticia", "5000.00")
                    .add("2026-09-18", CashFlowRole.INCOME, "SELF_EMPLOYED", "cliente novo", "1200.00");
            assertThat(of(fixture.detect("2026-09-20"), InsightDetector.NEW_INCOME_SOURCE)).isEmpty();
        }
    }

    @Nested
    class UncategorizedShare {

        @Test
        void fifteenPercentAndTwoHundred() {
            InsightFixture fixture = new InsightFixture().withoutHealth()
                    .add("2026-09-08", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1700.00")
                    .add("2026-09-09", CashFlowRole.EXPENSE, null, "loja", "300.00");
            InsightCandidate uncategorized = only(fixture.detect("2026-09-20"), InsightDetector.UNCATEGORIZED_SHARE);
            assertThat(uncategorized.content().title()).isEqualTo("15% dos seus gastos deste mês estão sem categoria.");
            assertThat(uncategorized.content().support()).isEqualTo(
                    "1 transação, R$ 300,00. Categorizar deixa as comparações e os insights mais precisos.");
            assertThat(uncategorized.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(primary(uncategorized).params()).isEqualTo(Map.of("category", "none"));

            InsightFixture below = new InsightFixture().withoutHealth()
                    .add("2026-09-08", CashFlowRole.EXPENSE, "HOUSING", "imobiliaria", "1701.00")
                    .add("2026-09-09", CashFlowRole.EXPENSE, null, "loja", "300.00");
            assertThat(of(below.detect("2026-09-20"), InsightDetector.UNCATEGORIZED_SHARE)).isEmpty();
        }
    }

    @Test
    void noActivityNoInsights() {
        assertThat(new InsightFixture().detect("2026-09-20")).isEmpty();
    }
}
