package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.usecase.AnalysisQueries;
import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.WORKSPACE;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.assertNoLeak;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.brl;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.decision;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.request;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryToolTest {

    private static final String MERCHANT_KEY = "chave-interna-xyz";

    private final AnalysisQueries analysis = mock(AnalysisQueries.class);
    private final CategoryTool tool = new CategoryTool(analysis);
    private final AnalysisViews.Meta meta = CashFlowToolTest.meta(DataState.READY, ProjectionState.UP_TO_DATE);

    private AnalysisViews.CategoryLine line(String code, String amount, String share, String previous) {
        var current = brl(amount);
        return new AnalysisViews.CategoryLine(code, current, new BigDecimal(share), 4, brl("0"),
                Variation.of(current, brl(previous)), null);
    }

    @Test
    void listsCategoriesWithShareAndVariation() {
        when(analysis.categories(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Categories(meta, brl("3000"),
                List.of(line("FOOD", "1200", "40.0", "1000"), line(null, "300", "10.0", "300"))));
        Pseudonyms pseudonyms = new Pseudonyms();

        var result = tool.collect(request(decision(Intent.CATEGORY, null, null, null, null), pseudonyms));

        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Despesa total do período").value()).isEqualTo("3000");
        assertThat(value(result, "Nome de categoria_1").value()).isEqualTo("Alimentação");
        assertThat(value(result, "Nome de categoria_1").unit()).isEqualTo(ValueUnit.TEXT);
        assertThat(value(result, "Despesa em categoria_1").value()).isEqualTo("1200");
        assertThat(value(result, "Participação de categoria_1 na despesa total").value()).isEqualTo("40.0");
        assertThat(value(result, "Variação percentual de despesa em categoria_1 contra o mês anterior").value())
                .isEqualTo("20.0");
        assertThat(value(result, "Nome de categoria_2").value()).isEqualTo("Sem categoria");
        assertThat(result.actions()).isEmpty();
        verify(analysis, never()).category(any(), any(), any());
    }

    @Test
    void limitsToEightCategories() {
        var lines = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> line("FOOD" + i, "100", "10.0", "100")).toList();
        when(analysis.categories(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Categories(meta, brl("1000"),
                lines));

        var result = tool.collect(request(Intent.CATEGORY));

        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Despesa em ")).hasSize(8);
    }

    @Test
    void addsTheRequestedCategoryDetailAndAction() {
        when(analysis.categories(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Categories(meta, brl("3000"),
                List.of(line("FOOD", "1200", "40.0", "1000"))));
        var amount = brl("1200");
        when(analysis.category(eq(WORKSPACE), eq("FOOD"), any())).thenReturn(new AnalysisViews.CategoryDetail(meta,
                "FOOD", "EXPENSE", new AnalysisViews.Metric(amount, Variation.of(amount, brl("1000")), null), 8,
                brl("150"),
                List.of(new AnalysisViews.CategoryMonth(YearMonth.of(2026, 10), false, brl("1000"), 7),
                        new AnalysisViews.CategoryMonth(YearMonth.of(2026, 11), true, brl("1200"), 8)),
                List.of(new AnalysisViews.MerchantAmount(MERCHANT_KEY, "Padaria Doce 24h", brl("400"), 5),
                        new AnalysisViews.MerchantAmount(null, null, brl("50"), 1))));
        Pseudonyms pseudonyms = new Pseudonyms();

        var result = tool.collect(request(decision(Intent.CATEGORY, null, "FOOD", null, null), pseudonyms));

        assertThat(value(result, "Transações da categoria categoria_1 no período").value()).isEqualTo("8");
        assertThat(value(result, "Ticket médio da categoria categoria_1").value()).isEqualTo("150");
        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Valor mensal da categoria")).hasSize(2);
        assertThat(value(result, "Comerciante primeiro da categoria categoria_1").value())
                .isEqualTo("Padaria Doce 24h");
        assertThat(value(result, "Valor no comerciante primeiro da categoria categoria_1").value())
                .isEqualTo("400");
        assertThat(result.quality().monthsWithData()).isEqualTo(2);
        assertThat(result.actions()).containsExactly(new OfferedAction(ActionKind.OPEN_ANALYSIS_CATEGORY,
                "categoria_1"));
        assertNoLeak(result, MERCHANT_KEY);
    }

    @Test
    void reportsNoDataAndNotBuilt() {
        when(analysis.categories(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Categories(
                CashFlowToolTest.meta(DataState.NO_DATA, ProjectionState.UP_TO_DATE), null, List.of()));
        assertThat(tool.collect(request(Intent.CATEGORY)).status()).isEqualTo(FinancialTool.Status.NO_DATA);

        when(analysis.categories(eq(WORKSPACE), any())).thenReturn(new AnalysisViews.Categories(
                CashFlowToolTest.meta(DataState.NO_DATA, ProjectionState.NOT_BUILT), null, List.of()));
        assertThat(tool.collect(request(Intent.CATEGORY)).status()).isEqualTo(FinancialTool.Status.NOT_BUILT);
    }
}
