package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.health.application.dto.HealthViews;
import com.smarthealthfinance.health.application.usecase.HealthQueries;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.HealthFreshnessState;
import com.smarthealthfinance.health.domain.enums.HealthState;
import com.smarthealthfinance.health.domain.enums.InsufficientReason;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.HealthFreshness;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.BRL;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.WORKSPACE;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.assertNoLeak;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.request;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthToolTest {

    private static final String SUMMARY_TEXT = "RESUMO PRONTO DO SCORE";
    private static final String EXPLANATION = "EXPLICACAO PRONTA DA DIMENSAO";

    private final HealthQueries health = mock(HealthQueries.class);
    private final HealthTool tool = new HealthTool(health);

    private static HealthResult result(YearMonth month, Integer score, boolean estimated, HealthState state,
                                       List<DimensionResult> dimensions) {
        return new HealthResult("health-v2", month, LocalDate.of(2026, 11, 10), true, LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 10, 31), 6, state, score, score == null ? null : 4, estimated, Confidence.HIGH,
                new BigDecimal("7.5"), new HealthResult.Coverage(2, 6, 45), 2,
                score == null ? null : SUMMARY_TEXT, dimensions, List.of(), List.of());
    }

    private static List<DimensionResult> dimensions() {
        return List.of(DimensionResult.ok(HealthDimension.CASH_FLOW, 82, EXPLANATION, false, List.of()),
                DimensionResult.ok(HealthDimension.RESERVE, 40, EXPLANATION, true, List.of()),
                DimensionResult.insufficient(HealthDimension.DEBT, InsufficientReason.REQUIRES_CARDS));
    }

    private static HealthFreshness freshness(HealthFreshnessState state) {
        return new HealthFreshness(state, null, null);
    }

    @Test
    void collectsScoreDimensionsAndEvolutionWithoutReadyMadeTexts() {
        YearMonth current = YearMonth.of(2026, 11);
        var summary = new HealthViews.Summary(current, result(current, 71, false, HealthState.READY, dimensions()),
                freshness(HealthFreshnessState.RECALCULATING), Set.of(DataSource.OFX, DataSource.MANUAL), BRL);
        when(health.summary(eq(WORKSPACE), any())).thenReturn(summary);
        when(health.evolution(eq(WORKSPACE), any(), eq(6))).thenReturn(new HealthViews.Evolution(current,
                List.of(new HealthViews.Month(YearMonth.of(2026, 9), null),
                        new HealthViews.Month(YearMonth.of(2026, 10),
                                result(YearMonth.of(2026, 10), 67, false, HealthState.READY, dimensions())),
                        new HealthViews.Month(current, summary.result())), freshness(HealthFreshnessState.UP_TO_DATE)));

        var result = tool.collect(request(Intent.HEALTH));

        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Score geral de saúde financeira").value()).isEqualTo("71");
        assertThat(value(result, "Score geral de saúde financeira").kind()).isEqualTo(ValueKind.FACT);
        assertThat(value(result, "Variação do score contra o mês anterior").value()).isEqualTo("4");
        assertThat(value(result, "Nota da dimensão Fluxo de caixa").value()).isEqualTo("82");
        assertThat(value(result, "Nota da dimensão Reserva").value()).isEqualTo("40");
        assertThat(result.values()).noneMatch(v -> v.label().contains("Dívidas"));
        assertThat(value(result, "Parcela do gasto sem categoria").value()).isEqualTo("7.5");
        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Score geral no mês da evolução")).hasSize(2);
        assertThat(result.quality().monthsWithData()).isEqualTo(6);
        assertThat(result.quality().partialPeriod()).isTrue();
        assertThat(result.quality().freshness()).isEqualTo(DataFreshness.UPDATING);
        assertThat(result.quality().sources()).containsExactlyInAnyOrder("OFX", "MANUAL");
        assertThat(result.sources().getFirst().version()).isEqualTo("health-v2");
        assertThat(result.breakdown().title()).isEqualTo("Notas por dimensão");
        assertThat(result.breakdown().totalIndex()).as("dimensões não têm total").isNull();
        assertThat(result.breakdown().rows()).extracting(row -> row.label())
                .containsExactly("Fluxo de caixa", "Reserva");
        assertThat(result.breakdown().rows()).extracting(row -> result.values().get(row.valueIndex()).label())
                .containsExactly("Nota da dimensão Fluxo de caixa", "Nota da dimensão Reserva");
        assertThat(result.actions()).containsExactly(new OfferedAction(ActionKind.OPEN_HEALTH, null));
        assertNoLeak(result, SUMMARY_TEXT, EXPLANATION);
    }

    @Test
    void marksAnEstimatedScore() {
        YearMonth current = YearMonth.of(2026, 11);
        when(health.summary(eq(WORKSPACE), any())).thenReturn(new HealthViews.Summary(current,
                result(current, 55, true, HealthState.READY, dimensions()),
                freshness(HealthFreshnessState.UP_TO_DATE), Set.of(), BRL));
        when(health.evolution(eq(WORKSPACE), any(), eq(6))).thenReturn(new HealthViews.Evolution(current, List.of(),
                freshness(HealthFreshnessState.UP_TO_DATE)));

        var result = tool.collect(request(Intent.HEALTH));

        assertThat(value(result, "Score geral de saúde financeira").kind()).isEqualTo(ValueKind.ESTIMATED);
    }

    @Test
    void reportsNoDataWithoutSnapshotAndNotBuiltWhenTheSnapshotIsMissing() {
        YearMonth current = YearMonth.of(2026, 11);
        when(health.summary(eq(WORKSPACE), any())).thenReturn(new HealthViews.Summary(current, null,
                freshness(HealthFreshnessState.UP_TO_DATE), Set.of(), BRL));
        assertThat(tool.collect(request(Intent.HEALTH)).status()).isEqualTo(FinancialTool.Status.NO_DATA);

        when(health.summary(eq(WORKSPACE), any())).thenReturn(new HealthViews.Summary(current, null,
                freshness(HealthFreshnessState.NOT_BUILT), Set.of(), BRL));
        assertThat(tool.collect(request(Intent.HEALTH)).status()).isEqualTo(FinancialTool.Status.NOT_BUILT);
    }

    @Test
    void reportsNoDataWhenThereIsNoScoreAndNoDimension() {
        YearMonth current = YearMonth.of(2026, 11);
        when(health.summary(eq(WORKSPACE), any())).thenReturn(new HealthViews.Summary(current,
                result(current, null, false, HealthState.INSUFFICIENT_DATA,
                        List.of(DimensionResult.insufficient(HealthDimension.CASH_FLOW,
                                InsufficientReason.REQUIRES_CARDS))),
                freshness(HealthFreshnessState.UP_TO_DATE), Set.of(), BRL));

        assertThat(tool.collect(request(Intent.HEALTH)).status()).isEqualTo(FinancialTool.Status.NO_DATA);
    }
}
