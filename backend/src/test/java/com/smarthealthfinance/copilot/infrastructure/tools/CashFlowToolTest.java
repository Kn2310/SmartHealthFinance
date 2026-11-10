package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader;
import com.smarthealthfinance.analytics.application.usecase.AnalysisQueries;
import com.smarthealthfinance.analytics.domain.enums.ComparisonBase;
import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.ComparisonPlan;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.WORKSPACE;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.brl;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.request;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CashFlowToolTest {

    private final AnalysisQueries analysis = mock(AnalysisQueries.class);
    private final CashFlowTool tool = new CashFlowTool(analysis);

    static AnalysisViews.Meta meta(DataState state, ProjectionState freshness) {
        AnalysisPeriod period = new AnalysisPeriod(YearMonth.of(2026, 11), LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 10), true);
        return new AnalysisViews.Meta(period, new ComparisonPlan(ComparisonBase.PREVIOUS, null, List.of(),
                List.of(), 5), new ProjectionFreshness(freshness, null, 0), state);
    }

    private static AnalysisViews.Metric metric(String amount, String previous) {
        var current = brl(amount);
        return new AnalysisViews.Metric(current, Variation.of(current, brl(previous)), null);
    }

    private static AnalysisViews.MonthBar bar(int month, String income, String expense) {
        return new AnalysisViews.MonthBar(YearMonth.of(2026, month), month == 11, brl(income), brl(expense),
                brl("0"), brl(income).minus(brl(expense)));
    }

    @Test
    void collectsMonthFlowVariationAndEvolution() {
        var meta = meta(DataState.READY, ProjectionState.UPDATING);
        when(analysis.overview(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Overview(meta,
                metric("5000", "4000"), metric("3000", "2000"), metric("100", "100"), metric("2100", "2100"),
                new java.math.BigDecimal("42.0"), List.of(), brl("2500"), null, null, List.of()));
        when(analysis.evolution(eq(WORKSPACE), any(), eq(6))).thenReturn(new AnalysisViews.Evolution(meta,
                List.of(bar(9, "4000", "2500"), bar(10, "4000", "2000"), bar(11, "5000", "3000"))));

        var result = tool.collect(request(IntentDecision.of(Intent.CASH_FLOW), new Pseudonyms()));

        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Receitas do mês").value()).isEqualTo("5000");
        assertThat(value(result, "Despesas do mês").period()).isEqualTo("2026-11-01..2026-11-10");
        assertThat(value(result, "Variação de despesas contra o mês anterior").value()).isEqualTo("1000");
        assertThat(value(result, "Variação percentual de despesas contra o mês anterior").value())
                .isEqualTo("50.0");
        assertThat(value(result, "Parcela da receita que sobrou no mês (resultado sobre receitas)").value())
                .isEqualTo("42.0");
        assertThat(value(result, "Despesa média mensal dos meses de comparação").value()).isEqualTo("2500");
        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Despesas no mês da evolução")).hasSize(3);
        assertThat(result.quality().monthsWithData()).isEqualTo(3);
        assertThat(result.quality().partialPeriod()).isTrue();
        assertThat(result.quality().freshness()).isEqualTo(DataFreshness.UPDATING);
        assertThat(result.sources()).extracting("tool").containsExactly("AnalysisQueries");
    }

    @Test
    void usesTheRequestedMonth() {
        var meta = meta(DataState.NO_DATA, ProjectionState.UP_TO_DATE);
        when(analysis.overview(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Overview(meta, null, null, null,
                null, null, null, null, null, null, null));

        tool.collect(request(new IntentDecision(Intent.CASH_FLOW, YearMonth.of(2026, 9), null, null, null),
                new Pseudonyms()));

        verify(analysis).overview(WORKSPACE, new AnalysisContextLoader.Query("2026-09", null, null));
    }

    @Test
    void reportsNoData() {
        when(analysis.overview(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Overview(
                meta(DataState.NO_DATA, ProjectionState.UP_TO_DATE), null, null, null, null, null, null, null,
                null, null, null));

        assertThat(tool.collect(request(Intent.CASH_FLOW)).status()).isEqualTo(FinancialTool.Status.NO_DATA);
    }

    @Test
    void reportsNotBuiltWhenTheProjectionIsMissing() {
        when(analysis.overview(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Overview(
                meta(DataState.NO_DATA, ProjectionState.NOT_BUILT), null, null, null, null, null, null, null,
                null, null, null));

        assertThat(tool.collect(request(Intent.CASH_FLOW)).status()).isEqualTo(FinancialTool.Status.NOT_BUILT);
    }
}
