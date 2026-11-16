package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.domain.model.ModelPrice;
import com.smarthealthfinance.copilot.domain.model.PricingTable;
import com.smarthealthfinance.copilot.infrastructure.config.RealCopilotWiring;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O relatório e o runner da avaliação real (ADR-0042 §3), sem rede: o relatório só tem ids e códigos, percentis e
 * seleção de casos, e o perfil sem chave falha com a mensagem certa em vez de cair no simulado.
 */
class CopilotEvaluationReportTest {

    private static final PricingTable PRICING = PricingTable.of("pricing-report-test", List.of(
            new ModelPrice(ScriptedGateway.INTENT_MODEL, new ModelPrice.Rates(new BigDecimal("1.00"),
                    new BigDecimal("5.00"), BigDecimal.ZERO, BigDecimal.ZERO), 0, null),
            new ModelPrice(ScriptedGateway.ANSWER_MODEL, new ModelPrice.Rates(new BigDecimal("3.00"),
                    new BigDecimal("15.00"), BigDecimal.ZERO, BigDecimal.ZERO), 0, null)));

    private static List<CopilotEvaluationReport.Run> runs(String... ids) {
        List<CopilotEvaluationReport.Run> runs = new ArrayList<>();
        for (String id : ids) {
            GoldenCase golden = GoldenSet.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
            ScriptedGateway gateway = new ScriptedGateway(golden);
            GoldenHarness.Outcome outcome = GoldenHarness.run(golden, gateway, GoldenHarness.fixedClock(), PRICING);
            runs.add(new CopilotEvaluationReport.Run(golden, outcome, GoldenChecks.checkReal(golden, outcome)));
        }
        return runs;
    }

    @Test
    void theReportHoldsOnlyIdsAndCodesNeverTheTextOfTheConversation() {
        List<CopilotEvaluationReport.Run> runs = runs("overview-total-balance", "invoice-aurora-october-plus-520-60",
                "invented-number-twice-falls-to-safe-mode", "transfer-money");
        var meta = new CopilotEvaluationReport.Meta("golden-v1", "pricing-report-test", "haiku", "sonnet",
                new BigDecimal("2.00"), null, 1, 70);

        JsonNode report = CopilotEvaluationReport.json(JsonMapper.builder().build(), meta, runs);
        String json = report.toString();
        String markdown = CopilotEvaluationReport.markdown(report);

        for (CopilotEvaluationReport.Run run : runs) {
            GoldenCase golden = run.golden();
            assertThat(json).contains(golden.id()).doesNotContain(golden.question());
            assertThat(markdown).doesNotContain(golden.question());
            if (golden.note() != null) {
                assertThat(json).doesNotContain(golden.note());
            }
        }
        assertThat(json + markdown).doesNotContain("R$ ", "Aurora Platinum", "18.420", "3.500");
        assertThat(report.get("cases").get("evaluated").asInt()).isEqualTo(4);
        assertThat(report.get("cases").get("skipped").asInt()).isEqualTo(1);
        assertThat(report.get("quality").get("safeMode").asInt()).as("o modo seguro conta como falha").isEqualTo(1);
        assertThat(report.get("claimViolationsByCode").get("DIGIT_OUTSIDE_REF").asLong()).isEqualTo(2);
        assertThat(report.get("accuracyByIntent").get("CARDS").get("evaluated").asInt()).isEqualTo(2);
        assertThat(report.get("costUsd").get("total").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(report.get("costUsd").get("byCase").has("overview-total-balance")).isTrue();
        assertThat(report.get("latencyMs").get("answer").get("calls").asInt()).isEqualTo(4);
        assertThat(report.get("versions").get("answerPrompt").asString()).startsWith("answer-v2@");
        assertThat(markdown).contains("# Avaliação do Copilot com o modelo real", "## Acerto por intenção",
                "invented-number-twice-falls-to-safe-mode");
    }

    @Test
    void percentilesUseTheNearestRank() {
        List<Long> sorted = List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L);

        assertThat(CopilotEvaluationReport.percentile(sorted, 50)).isEqualTo(500);
        assertThat(CopilotEvaluationReport.percentile(sorted, 95)).isEqualTo(1000);
        assertThat(CopilotEvaluationReport.percentile(List.of(), 95)).isZero();
        assertThat(CopilotEvaluationReport.percentile(List.of(7L), 50)).isEqualTo(7);
    }

    @Test
    void casesAreSelectedByIdGroupOrExpectedIntent() {
        List<GoldenCase> all = GoldenSet.cases();

        assertThat(CopilotRealEvaluation.select(all, null)).hasSameSizeAs(all);
        assertThat(CopilotRealEvaluation.select(all, "  ")).hasSameSizeAs(all);
        assertThat(CopilotRealEvaluation.select(all, "transfer-money, blank-question")).extracting(GoldenCase::id)
                .containsExactly("transfer-money", "blank-question");
        assertThat(CopilotRealEvaluation.select(all, "group:continuity")).isNotEmpty()
                .allMatch(golden -> golden.group().equals("continuity"));
        assertThat(CopilotRealEvaluation.select(all, "intent:GOAL_SIMULATION, goal-simulation-lisbon-900"))
                .extracting(GoldenCase::id).doesNotHaveDuplicates().contains("goal-simulation-lisbon-900");
        assertThatThrownBy(() -> CopilotRealEvaluation.select(all, "no-such-case"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no-such-case");
    }

    @Test
    void theRealWiringUsesTheProductionConfigurationAndThePricesOfBothModelsWithoutCallingTheNetwork() {
        RealCopilotWiring.Wiring wiring = RealCopilotWiring.anthropic("sk-test-not-real");

        assertThat(wiring.intentModel()).isEqualTo("claude-haiku-5-5");
        assertThat(wiring.answerModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(wiring.pricing().version()).startsWith("pricing-");
        assertThat(wiring.pricing().find(wiring.intentModel())).isPresent();
        assertThat(wiring.pricing().find(wiring.answerModel())).isPresent();
        assertThat(wiring.gateways().apply(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .isNotNull();
    }

    @Test
    void withoutTheKeyInTheEnvironmentTheRealWiringStopsWithAClearMessageAndNeverFallsBack() {
        Assumptions.assumeTrue(System.getenv(RealCopilotWiring.KEY_VARIABLE) == null
                || System.getenv(RealCopilotWiring.KEY_VARIABLE).isBlank(), "a key is set in this environment");

        assertThatThrownBy(RealCopilotWiring::anthropic).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY").hasMessageContaining("never falls back")
                .hasMessageNotContaining("sk-");
    }
}
