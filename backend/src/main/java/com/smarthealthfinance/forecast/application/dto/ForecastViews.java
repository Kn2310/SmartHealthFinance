package com.smarthealthfinance.forecast.application.dto;

import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesEligibility;
import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.ForecastFreshness;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.NextIncome;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Visões de leitura do Forecast (ADR-0032): o que o snapshot gravou, com o envelope comum. */
public final class ForecastViews {

    private ForecastViews() {
    }

    /** Estado dos dados da leitura (ADR-0032 §1). */
    public enum State {
        READY,
        /** Sem transações. */
        NO_DATA,
        /** Só os fatos; as estimativas vêm nulas. */
        INSUFFICIENT_HISTORY,
        /** Sem snapshot: os blocos vêm nulos. */
        NOT_BUILT
    }

    /**
     * @param body nulo quando não há o que mostrar (sem snapshot, sem histórico para o bloco, ou sem dado)
     * @param asOf data-base do snapshot; nula sem snapshot
     */
    public record View<T>(State state, ForecastFreshness freshness, String algorithmVersion, LocalDate asOf, T body) {}

    /** Id opaco da API por (sentido, chave): a chave do comerciante nunca sai do módulo. */
    public record SeriesIds(Map<String, UUID> byKey) {

        public static String keyOf(SeriesDirection direction, String key) {
            return direction + "|" + key;
        }

        /** Nulo se a série não existe mais. */
        public UUID of(SeriesDirection direction, String key) {
            return key == null ? null : byKey.get(keyOf(direction, key));
        }
    }

    public record Month(MonthProjection month, SeriesIds ids) {}

    public record Income(NextIncome income, UUID seriesId) {}

    public record Upcoming(List<UpcomingEvent> events, SeriesIds ids) {}

    public record Invoices(List<CardInvoiceProjection> invoices) {}

    public record CashFlow(CashFlowProjection cashFlow) {}

    /**
     * Uma série para a API. {@code eligibility}, {@code realizedThisMonth} e {@code expectedDate} vêm do último
     * snapshot e ficam nulos se a série ainda não estava nele (recém-detectada).
     */
    public record RecurringItem(UUID id, RecurringSeries series, SeriesEligibility eligibility,
                                Boolean realizedThisMonth, LocalDate expectedDate) {}
}
