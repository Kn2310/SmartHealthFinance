package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import com.smarthealthfinance.forecast.application.dto.ForecastViews;
import com.smarthealthfinance.forecast.application.dto.ForecastViews.View;
import com.smarthealthfinance.forecast.application.usecase.ForecastQueries;
import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.ForecastFreshnessState;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.NextIncome;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * {@code FORECAST} (ADR-0039 §4) sobre {@link ForecastQueries#month}, {@code upcoming}, {@code cashFlow} e
 * {@code nextIncome}: projeção do mês, próximos eventos (até 5), menor saldo dos próximos 30 dias, saldo projetado
 * por mês e próxima receita. O que é projetado entra como {@code ESTIMATED}; fatos de fatura, como {@code FACT}.
 * Os rótulos livres do módulo ({@code UpcomingEvent.label}, {@code NextIncome.label}) podem vir de texto digitado e
 * nunca entram: o evento é descrito pelo tipo e, nas faturas, pelo {@code cartao_N} do catálogo da pergunta.
 * {@code RECALCULATING} vira {@code UPDATING}; {@code NOT_BUILT}, status {@code NOT_BUILT}.
 */
@Component
public class ForecastTool implements FinancialTool {

    static final int MAX_EVENTS = 5;

    private final ForecastQueries forecast;

    public ForecastTool(ForecastQueries forecast) {
        this.forecast = forecast;
    }

    @Override
    public Intent intent() {
        return Intent.FORECAST;
    }

    @Override
    public Result collect(Request request) {
        View<ForecastViews.Month> month = forecast.month(request.workspaceId());
        if (month.state() == ForecastViews.State.NOT_BUILT
                || month.freshness().state() == ForecastFreshnessState.NOT_BUILT) {
            return Result.of(Status.NOT_BUILT);
        }
        if (month.state() == ForecastViews.State.NO_DATA) {
            return Result.of(Status.NO_DATA);
        }
        View<ForecastViews.Upcoming> upcoming = forecast.upcoming(request.workspaceId());
        View<ForecastViews.CashFlow> cashFlow = forecast.cashFlow(request.workspaceId());
        View<ForecastViews.Income> income = forecast.nextIncome(request.workspaceId());
        if (month.body() == null && upcoming.body() == null && cashFlow.body() == null && income.body() == null) {
            return Result.of(Status.NO_DATA);
        }

        ToolValues.Builder values = new ToolValues.Builder(month.algorithmVersion());
        List<String> gaps = List.of();
        Integer baseMonths = null;
        if (month.body() != null) {
            MonthProjection projection = month.body().month();
            baseMonths = projection.baseMonths();
            gaps = projection.expense().limitations().stream().map(Enum::name).toList();
            String period = ToolValues.month(projection.month());
            projected(values, "Despesa projetada do mês", projection.expense(), period);
            projected(values, "Receita projetada do mês", projection.income(), period);
            projected(values, "Resultado projetado do mês", projection.net(), period);
            values.money("Parte da despesa do mês já realizada", projection.expense().factPart(), period)
                    .money("Parte da receita do mês já realizada", projection.income().factPart(), period);
        }
        if (cashFlow.body() != null) {
            cashFlow(values, cashFlow.body().cashFlow());
        }
        if (upcoming.body() != null) {
            events(request, values, upcoming.body().events());
        }
        if (income.body() != null) {
            nextIncome(values, income.body().income());
        }

        DataFreshness freshness = freshness(month.freshness().state());
        DataQuality quality = new DataQuality(baseMonths, true, gaps, freshness, Set.of());
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("ForecastQueries", month.algorithmVersion())),
                List.of(new OfferedAction(ActionKind.OPEN_FORECAST, null)));
    }

    private static void cashFlow(ToolValues.Builder values, CashFlowProjection projection) {
        if (projection == null) {
            return;
        }
        CashFlowProjection.BalancePoint lowest = projection.troughNext30().lowestCentral();
        if (lowest != null) {
            String day = lowest.date().toString();
            values.money("Menor saldo central projetado nos próximos dias", lowest.central(), ValueKind.ESTIMATED,
                            day)
                    .date("Data do menor saldo central projetado nos próximos dias", lowest.date(),
                            ValueKind.ESTIMATED)
                    .money("Menor saldo projetado no limite inferior da faixa nos próximos dias",
                            projection.troughNext30().lowestLowBound() == null ? null
                                    : projection.troughNext30().lowestLowBound().low(), ValueKind.ESTIMATED, day);
        }
        projection.months().forEach(point -> values.money("Saldo projetado no fim do mês", point.closing().value(),
                kind(point.closing()), ToolValues.month(point.month())));
    }

    private void events(Request request, ToolValues.Builder values, List<UpcomingEvent> events) {
        List<UpcomingEvent> shown = events.stream().limit(MAX_EVENTS).toList();
        for (int i = 0; i < shown.size(); i++) {
            UpcomingEvent event = shown.get(i);
            String subject = "do " + ToolSupport.ordinal(i) + " próximo evento (" + describe(event.kind())
                    + (event.channel().isCard() ? ToolSupport.cardPseudonym(request.pseudonyms(),
                    event.channel().id()).map(pseudonym -> " de " + pseudonym).orElse("") : "") + ")";
            ValueKind kind = kind(event.value());
            String day = event.date().toString();
            values.date("Data " + subject, event.date(), kind)
                    .money("Valor " + subject, event.value().value(), kind, day)
                    .money("Estimativa no fechamento da fatura " + subject,
                            event.closingEstimate() == null ? null : event.closingEstimate().value(),
                            ValueKind.ESTIMATED, day);
        }
    }

    private static void nextIncome(ToolValues.Builder values, NextIncome income) {
        if (income == null) {
            return;
        }
        String day = income.date().toString();
        values.money("Próxima receita prevista", income.amount().value(), ValueKind.ESTIMATED, day)
                .date("Data da próxima receita prevista", income.date(), ValueKind.ESTIMATED)
                .money("Menor valor da faixa da próxima receita prevista", income.amount().low(),
                        ValueKind.ESTIMATED, day)
                .money("Maior valor da faixa da próxima receita prevista", income.amount().high(),
                        ValueKind.ESTIMATED, day);
    }

    private static void projected(ToolValues.Builder values, String label, Projected projected, String period) {
        ValueKind kind = kind(projected);
        values.money(label, projected.value(), kind, period);
        if (projected.basis() != Basis.FACT) {
            values.money("Menor valor da faixa: " + label.toLowerCase(), projected.low(), kind, period)
                    .money("Maior valor da faixa: " + label.toLowerCase(), projected.high(), kind, period);
        }
    }

    private static ValueKind kind(Projected projected) {
        return projected.basis() == Basis.FACT ? ValueKind.FACT : ValueKind.ESTIMATED;
    }

    private static String describe(UpcomingEventKind kind) {
        return switch (kind) {
            case INVOICE_PARTIAL -> "fatura parcial";
            case INVOICE_OPEN -> "fatura aberta";
            case INVOICE_OVERDUE -> "fatura vencida";
            case RECURRING_INCOME -> "receita recorrente";
            case RECURRING_EXPENSE -> "despesa recorrente";
        };
    }

    private static DataFreshness freshness(ForecastFreshnessState state) {
        return switch (state) {
            case UP_TO_DATE -> DataFreshness.UP_TO_DATE;
            case RECALCULATING -> DataFreshness.UPDATING;
            case NOT_BUILT -> DataFreshness.NOT_BUILT;
        };
    }
}
