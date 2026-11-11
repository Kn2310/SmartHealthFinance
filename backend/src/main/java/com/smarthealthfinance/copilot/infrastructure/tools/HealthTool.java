package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import com.smarthealthfinance.health.application.dto.HealthViews;
import com.smarthealthfinance.health.application.usecase.HealthQueries;
import com.smarthealthfinance.health.domain.enums.HealthFreshnessState;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.HealthResult;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code HEALTH} (ADR-0039 §4) sobre {@link HealthQueries#summary} e {@link HealthQueries#evolution}: score geral e
 * variação, nota de cada dimensão com nome em pt-BR, % do gasto sem categoria e a série de scores dos últimos 6
 * meses. Os textos prontos ({@code summary}, {@code explanation}, fatores, próximos passos, evidências) nunca entram:
 * o modelo explica a partir dos números. Score estimado vai como {@code ESTIMATED}.
 */
@Component
public class HealthTool implements FinancialTool {

    static final int EVOLUTION_MONTHS = 6;

    private final HealthQueries health;

    public HealthTool(HealthQueries health) {
        this.health = health;
    }

    @Override
    public Intent intent() {
        return Intent.HEALTH;
    }

    @Override
    public Result collect(Request request) {
        String month = request.decision().monthValue().map(YearMonth::toString).orElse(null);
        HealthViews.Summary summary = health.summary(request.workspaceId(), month);
        HealthResult result = summary.result();
        if (result == null) {
            return Result.of(summary.freshness().state() == HealthFreshnessState.NOT_BUILT ? Status.NOT_BUILT : Status.NO_DATA);
        }
        boolean anyDimension = result.dimensions().stream().anyMatch(DimensionResult::isOk);
        if (result.score() == null && !anyDimension) {
            return Result.of(Status.NO_DATA);
        }
        HealthViews.Evolution evolution = health.evolution(request.workspaceId(), month, EVOLUTION_MONTHS);

        String period = ToolValues.month(summary.month());
        String source = result.algorithmVersion();
        ValueKind scoreKind = result.estimated() ? ValueKind.ESTIMATED : ValueKind.FACT;
        ToolValues.Builder values = new ToolValues.Builder(source)
                .score("Score geral de saúde financeira", result.score(), scoreKind, period)
                .score("Variação do score contra o mês anterior", result.variation(), scoreKind, period);
        List<BreakdownDraft.Row> rows = new ArrayList<>();
        for (DimensionResult dimension : result.dimensions()) {
            if (dimension.isOk()) {
                rows.add(new BreakdownDraft.Row(dimension.dimension().label(), values.size(), null));
                values.score("Nota da dimensão " + dimension.dimension().label(), dimension.score(),
                        ValueKind.FACT, period);
            }
        }
        values.percent("Parcela do gasto sem categoria", result.uncategorizedShare(), period);

        for (HealthViews.Month point : evolution.months()) {
            if (point.result() != null && point.result().score() != null) {
                values.score("Score geral no mês da evolução", point.result().score(),
                        point.result().estimated() ? ValueKind.ESTIMATED : ValueKind.FACT,
                        ToolValues.month(point.month()));
            }
        }
        Set<String> sources = summary.sources().stream().map(Enum::name).collect(Collectors.toSet());
        DataQuality quality = new DataQuality(result.monthsUsed(), result.partial(), List.of(),
                freshness(summary.freshness().state()), sources);
        BreakdownDraft breakdown = rows.isEmpty() ? null
                : new BreakdownDraft("Notas por dimensão", rows, null);
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("HealthQueries", source)), List.of(new OfferedAction(ActionKind.OPEN_HEALTH,
                null)), breakdown);
    }

    private static DataFreshness freshness(HealthFreshnessState state) {
        return switch (state) {
            case UP_TO_DATE -> DataFreshness.UP_TO_DATE;
            case RECALCULATING -> DataFreshness.UPDATING;
            case NOT_BUILT -> DataFreshness.NOT_BUILT;
        };
    }
}
