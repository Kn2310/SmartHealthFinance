package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.application.dto.InsightViews;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.usecase.InsightQueries;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.InsightsFreshnessState;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.WORKSPACE;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.assertNoLeak;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.brl;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.request;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightsToolTest {

    private static final String TITLE = "TITULO PRONTO DO INSIGHT";
    private static final String SUPPORT = "APOIO PRONTO DO INSIGHT";
    private static final String CARD_NICKNAME = "Roxinho da Ana";
    private static final UUID INSIGHT_ID = UUID.fromString("a0f0c0de-1111-4222-8333-444455556666");

    private final InsightQueries insights = mock(InsightQueries.class);
    private final InsightsTool tool = new InsightsTool(insights);

    private static Insight insight(UUID id, InsightDetector detector, Importance importance, String subjectLabel,
                                   String impactAmount, Integer impactPoints) {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        var content = new InsightContent(TITLE, SUPPORT, subjectLabel, null, null,
                new InsightEvidence(List.of(), List.of(), List.of(), "comparacao", List.of(), List.of()),
                List.of(InsightAction.primary(com.smarthealthfinance.insights.domain.enums.ActionKind.VIEW_ANALYSIS,
                        "Ver", Map.of())), InsightRefs.none());
        return new Insight(id, WORKSPACE, "fingerprint", "insights-v3", detector, "2026-11", "subject",
                InsightStatus.ACTIVE, null, importance, 50, 50, Confidence.HIGH, 10,
                impactAmount == null ? null : brl(impactAmount), impactPoints, false, content, null, null, null, null,
                1, now, now, now);
    }

    private static InsightViews.Page page(InsightViews.DataState state, InsightsFreshnessState freshness,
                                          List<Insight> items) {
        return new InsightViews.Page(state, new InsightsFreshness(freshness, null, null),
                new InsightViews.Period(YearMonth.of(2026, 11), true),
                new InsightStore.ChipCounts(items.size(), 0, 0, 0, 0, 0), items, 0, 8, items.size());
    }

    @Test
    void describesInsightsWithoutReadyMadeTexts() {
        when(insights.list(eq(WORKSPACE), any(), any(), any(), eq(0), eq(8))).thenReturn(page(
                InsightViews.DataState.READY, InsightsFreshnessState.UPDATING, List.of(
                        insight(INSIGHT_ID, InsightDetector.CATEGORY_SPIKE, Importance.IMPORTANT, "Alimentação",
                                "320.5", null),
                        insight(UUID.randomUUID(), InsightDetector.CARD_UTILIZATION_HIGH, Importance.INFORMATIVE,
                                CARD_NICKNAME, null, 6),
                        insight(UUID.randomUUID(), InsightDetector.HEALTH_SCORE_CHANGE, Importance.INFORMATIVE,
                                "Saúde financeira", null, null))));
        Pseudonyms pseudonyms = new Pseudonyms();

        var result = tool.collect(request(new com.smarthealthfinance.copilot.domain.model.IntentDecision(
                Intent.INSIGHTS, null, null, null, null), pseudonyms));

        String first = "insight_1, alta de gasto em uma categoria, importante";
        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Impacto financeiro de " + first).value()).isEqualTo("320.5");
        assertThat(value(result, "Assunto de " + first).value()).isEqualTo("Alimentação");
        assertThat(value(result, "Assunto de " + first).unit()).isEqualTo(ValueUnit.TEXT);
        String second = "insight_2, utilização alta do limite do cartão, informativo";
        assertThat(value(result, "Impacto em pontos de " + second).value()).isEqualTo("6");
        assertThat(result.values()).noneMatch(v -> v.label().startsWith("Assunto de " + second));
        assertThat(value(result, "Insight insight_3, mudança no score de saúde financeira, informativo").unit())
                .isEqualTo(ValueUnit.TEXT);
        assertThat(result.actions()).containsExactly(new OfferedAction(ActionKind.OPEN_INSIGHT, "insight_1"),
                new OfferedAction(ActionKind.OPEN_INSIGHT, "insight_2"),
                new OfferedAction(ActionKind.OPEN_INSIGHT, "insight_3"));
        assertThat(result.quality().freshness()).isEqualTo(DataFreshness.UPDATING);
        assertThat(pseudonyms.find("insight_1")).isPresent();
        assertNoLeak(result, TITLE, SUPPORT, CARD_NICKNAME, INSIGHT_ID.toString());
    }

    @Test
    void marksProjectedInsightsAsEstimatedAndOffersAtMostThreeActions() {
        var items = IntStream.range(0, 8).mapToObj(i -> insight(UUID.randomUUID(),
                InsightDetector.BALANCE_MAY_GO_NEGATIVE, Importance.IMPORTANT, "Saldo projetado", "100", null))
                .toList();
        when(insights.list(eq(WORKSPACE), any(), any(), any(), any(), any())).thenReturn(page(
                InsightViews.DataState.READY, InsightsFreshnessState.UP_TO_DATE, items));

        var result = tool.collect(request(Intent.INSIGHTS));

        assertThat(result.values()).allMatch(v -> v.kind() == ValueKind.ESTIMATED);
        assertThat(result.values()).hasSize(8);
        assertThat(result.actions()).hasSize(3);
        verify(insights).list(WORKSPACE, null, null, null, 0, 8);
    }

    @Test
    void reportsNoDataAndNotBuilt() {
        when(insights.list(eq(WORKSPACE), any(), any(), any(), any(), any())).thenReturn(page(
                InsightViews.DataState.NO_DATA, InsightsFreshnessState.UP_TO_DATE, List.of()));
        assertThat(tool.collect(request(Intent.INSIGHTS)).status()).isEqualTo(FinancialTool.Status.NO_DATA);

        when(insights.list(eq(WORKSPACE), any(), any(), any(), any(), any())).thenReturn(page(
                InsightViews.DataState.INSUFFICIENT_HISTORY, InsightsFreshnessState.UP_TO_DATE, List.of()));
        assertThat(tool.collect(request(Intent.INSIGHTS)).status()).isEqualTo(FinancialTool.Status.NO_DATA);

        when(insights.list(eq(WORKSPACE), any(), any(), any(), any(), any())).thenReturn(page(
                InsightViews.DataState.NO_DATA, InsightsFreshnessState.NOT_BUILT, List.of()));
        assertThat(tool.collect(request(Intent.INSIGHTS)).status()).isEqualTo(FinancialTool.Status.NOT_BUILT);
    }
}
