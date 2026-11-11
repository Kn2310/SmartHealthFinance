package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.usecase.AnalysisQueries;
import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
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
import static org.mockito.Mockito.when;

class MerchantToolTest {

    private final AnalysisQueries analysis = mock(AnalysisQueries.class);
    private final MerchantTool tool = new MerchantTool(analysis);

    private static AnalysisViews.MerchantLine line(String key, String name, String category, String amount) {
        var current = brl(amount);
        return new AnalysisViews.MerchantLine(key, name, category, 6, current,
                Variation.of(current, brl("100")), null, LocalDate.of(2026, 11, 8));
    }

    @Test
    void listsMerchantsWithNormalizedNameAndCatalogCategory() {
        var meta = CashFlowToolTest.meta(DataState.READY, ProjectionState.UP_TO_DATE);
        when(analysis.merchants(eq(WORKSPACE), any(), any())).thenReturn(new AnalysisViews.Merchants(meta, "AMOUNT",
                List.of(line("chave-interna-1", "Mercado Central", "FOOD", "300"),
                        line("chave-interna-2", "Posto 24h", null, "120")), brl("45")));

        var result = tool.collect(request(Intent.MERCHANT));

        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Comerciante primeiro por valor").value()).isEqualTo("Mercado Central");
        assertThat(value(result, "Categoria do comerciante primeiro").value()).isEqualTo("Alimentação");
        assertThat(value(result, "Despesa no comerciante primeiro").value()).isEqualTo("300");
        assertThat(value(result, "Transações no comerciante primeiro").value()).isEqualTo("6");
        assertThat(value(result, "Variação percentual de despesa no comerciante primeiro contra o mês anterior")
                .value()).isEqualTo("200.0");
        assertThat(value(result, "Categoria do comerciante segundo").value()).isEqualTo("Sem categoria");
        assertThat(value(result, "Despesas sem comerciante identificado").value()).isEqualTo("45");
        assertThat(result.quality().partialPeriod()).isTrue();
        assertNoLeak(result, "chave-interna");
    }

    @Test
    void limitsToTenMerchants() {
        var meta = CashFlowToolTest.meta(DataState.READY, ProjectionState.UP_TO_DATE);
        var lines = IntStream.range(0, 12).mapToObj(i -> line("k" + i, "Loja", "FOOD", "10")).toList();
        when(analysis.merchants(eq(WORKSPACE), any(), any())).thenReturn(new AnalysisViews.Merchants(meta, "AMOUNT",
                lines, brl("0")));

        var result = tool.collect(request(Intent.MERCHANT));

        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Despesa no comerciante ")).hasSize(10);
    }

    @Test
    void reportsNoDataAndNotBuilt() {
        when(analysis.merchants(eq(WORKSPACE), any(), any())).thenReturn(new AnalysisViews.Merchants(
                CashFlowToolTest.meta(DataState.NO_DATA, ProjectionState.UP_TO_DATE), "AMOUNT", List.of(), null));
        assertThat(tool.collect(request(Intent.MERCHANT)).status()).isEqualTo(FinancialTool.Status.NO_DATA);

        when(analysis.merchants(eq(WORKSPACE), any(), any())).thenReturn(new AnalysisViews.Merchants(
                CashFlowToolTest.meta(DataState.NO_DATA, ProjectionState.NOT_BUILT), "AMOUNT", List.of(), null));
        assertThat(tool.collect(request(Intent.MERCHANT)).status()).isEqualTo(FinancialTool.Status.NOT_BUILT);
    }
}
