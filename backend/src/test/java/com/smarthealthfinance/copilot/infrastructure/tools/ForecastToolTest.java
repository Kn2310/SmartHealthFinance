package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.forecast.application.dto.ForecastViews;
import com.smarthealthfinance.forecast.application.dto.ForecastViews.State;
import com.smarthealthfinance.forecast.application.dto.ForecastViews.View;
import com.smarthealthfinance.forecast.application.usecase.ForecastQueries;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.ForecastFreshnessState;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastFreshness;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.NextIncome;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.WORKSPACE;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.assertNoLeak;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.brl;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.request;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForecastToolTest {

    private static final String SALARY_LABEL = "Salário Acme Ltda";
    private static final String CARD_NICKNAME = "Roxinho da Ana";
    private static final UUID CARD_ID = UUID.fromString("0b8f8a5e-4a3c-4d36-9a52-2f4a0e3c9d11");
    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private final ForecastQueries forecast = mock(ForecastQueries.class);
    private final ForecastTool tool = new ForecastTool(forecast);

    private static ForecastFreshness freshness(ForecastFreshnessState state) {
        return new ForecastFreshness(state, null, TODAY, null);
    }

    private static <T> View<T> view(State state, ForecastFreshnessState freshness, T body) {
        return new View<>(state, freshness(freshness), "forecast-v1", TODAY, body);
    }

    private static Projected estimated(String fact, String estimated, String min, String max) {
        return Projected.estimated(brl(fact), brl(estimated), new Range(brl(min), brl(max)), Confidence.MEDIUM,
                List.of(Limitation.SHORT_HISTORY), List.of());
    }

    private void stubReady(ForecastFreshnessState freshness) {
        var expense = estimated("2000", "1000", "800", "1300");
        var income = estimated("5000", "0", "0", "0");
        var net = income.minus(expense);
        var month = new MonthProjection(YearMonth.of(2026, 11), TODAY, 4, expense, income, net, List.of());
        when(forecast.month(WORKSPACE)).thenReturn(view(State.READY, freshness,
                new ForecastViews.Month(month, new ForecastViews.SeriesIds(java.util.Map.of()))));
        when(forecast.upcoming(WORKSPACE)).thenReturn(view(State.READY, freshness, new ForecastViews.Upcoming(List.of(
                new UpcomingEvent(UpcomingEventKind.INVOICE_OPEN, LocalDate.of(2026, 11, 12), null, CARD_NICKNAME,
                        Channel.card(CARD_ID), Projected.fact(brl("1200"), true, List.of(), List.of()),
                        estimated("1200", "300", "250", "400")),
                new UpcomingEvent(UpcomingEventKind.RECURRING_INCOME, LocalDate.of(2026, 11, 30), "chave-xyz",
                        SALARY_LABEL, Channel.account(ACCOUNT_ID), estimated("0", "5000", "5000", "5000"), null)),
                new ForecastViews.SeriesIds(java.util.Map.of()))));
        var point = new CashFlowProjection.BalancePoint(LocalDate.of(2026, 11, 20), brl("900"), brl("500"),
                brl("1200"));
        var trough = new CashFlowProjection.Trough(point, point);
        when(forecast.cashFlow(WORKSPACE)).thenReturn(view(State.READY, freshness, new ForecastViews.CashFlow(
                new CashFlowProjection(TODAY, LocalDate.of(2027, 2, 28), List.of(
                        new CashFlowProjection.CashFlowMonth(YearMonth.of(2026, 11), brl("3000"), brl("0"),
                                brl("0"), brl("0"), brl("0"), estimated("3000", "-500", "-900", "-200"))),
                        trough, trough, Confidence.MEDIUM, List.of(), List.of()))));
        when(forecast.nextIncome(WORKSPACE)).thenReturn(view(State.READY, freshness, new ForecastViews.Income(
                new NextIncome("chave-xyz", SALARY_LABEL, LocalDate.of(2026, 11, 30), Channel.account(ACCOUNT_ID),
                        estimated("0", "5000", "4800", "5200")), null)));
    }

    @Test
    void projectsMonthEventsCashFlowAndNextIncomeAsEstimated() {
        stubReady(ForecastFreshnessState.RECALCULATING);
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.CARD, CARD_ID.toString(), "VISA", CARD_NICKNAME);

        var result = tool.collect(request(com.smarthealthfinance.copilot.domain.model.IntentDecision
                .of(Intent.FORECAST), pseudonyms));

        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Despesa projetada do mês").value()).isEqualTo("3000");
        assertThat(value(result, "Despesa projetada do mês").kind()).isEqualTo(ValueKind.ESTIMATED);
        assertThat(value(result, "Menor valor da faixa: despesa projetada do mês").value()).isEqualTo("2800");
        assertThat(value(result, "Parte da despesa do mês já realizada").kind()).isEqualTo(ValueKind.FACT);
        assertThat(value(result, "Data do primeiro próximo evento (fatura aberta de cartao_1)").value())
                .isEqualTo("2026-11-12");
        assertThat(value(result, "Valor do primeiro próximo evento (fatura aberta de cartao_1)").kind())
                .isEqualTo(ValueKind.FACT);
        assertThat(value(result, "Estimativa no fechamento da fatura do primeiro próximo evento (fatura aberta de "
                + "cartao_1)").value()).isEqualTo("1500");
        assertThat(value(result, "Valor do segundo próximo evento (receita recorrente)").kind())
                .isEqualTo(ValueKind.ESTIMATED);
        assertThat(value(result, "Menor saldo central projetado nos próximos dias").value()).isEqualTo("900");
        assertThat(value(result, "Saldo projetado no fim do mês").period()).isEqualTo("2026-11");
        assertThat(value(result, "Próxima receita prevista").value()).isEqualTo("5000");
        assertThat(value(result, "Próxima receita prevista").kind()).isEqualTo(ValueKind.ESTIMATED);
        assertThat(result.quality().freshness()).isEqualTo(DataFreshness.UPDATING);
        assertThat(result.quality().monthsWithData()).isEqualTo(4);
        assertThat(result.quality().gaps()).containsExactly("SHORT_HISTORY");
        assertThat(result.sources().getFirst().version()).isEqualTo("forecast-v1");
        assertThat(result.actions()).containsExactly(new OfferedAction(ActionKind.OPEN_FORECAST, null));
        assertNoLeak(result, SALARY_LABEL, CARD_NICKNAME, "chave-xyz", CARD_ID.toString(), ACCOUNT_ID.toString());
    }

    @Test
    void doesNotNameAnInvoiceWhoseCardIsNotInTheCatalog() {
        stubReady(ForecastFreshnessState.UP_TO_DATE);

        var result = tool.collect(request(Intent.FORECAST));

        assertThat(result.values()).anyMatch(v -> v.label().equals("Data do primeiro próximo evento (fatura aberta)"));
        assertNoLeak(result, CARD_NICKNAME, CARD_ID.toString());
    }

    @Test
    void reportsNotBuiltAndNoData() {
        when(forecast.month(WORKSPACE)).thenReturn(view(State.NOT_BUILT, ForecastFreshnessState.NOT_BUILT, null));
        assertThat(tool.collect(request(Intent.FORECAST)).status()).isEqualTo(FinancialTool.Status.NOT_BUILT);

        when(forecast.month(WORKSPACE)).thenReturn(view(State.NO_DATA, ForecastFreshnessState.UP_TO_DATE, null));
        assertThat(tool.collect(request(Intent.FORECAST)).status()).isEqualTo(FinancialTool.Status.NO_DATA);
    }
}
