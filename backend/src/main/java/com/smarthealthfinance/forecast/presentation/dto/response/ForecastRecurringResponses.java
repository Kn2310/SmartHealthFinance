package com.smarthealthfinance.forecast.presentation.dto.response;

import com.smarthealthfinance.forecast.application.dto.ForecastViews.RecurringItem;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Recorrências (ADR-0032 §2). Os valores de topo são os efetivos (os do usuário, se houver); {@code detected} guarda os
 * detectados. Elegibilidade, "realizada no mês" e data esperada vêm do último snapshot. Schemas com o prefixo
 * {@code Forecast} (ADR-0010).
 */
public final class ForecastRecurringResponses {

    private ForecastRecurringResponses() {
    }

    public record ForecastDetectedDto(int expectedDay, MoneyDto expectedAmount,
                                      ForecastResponses.ForecastRangeDto amountRange, String confidence) {}

    /**
     * @param label            nome do comerciante ou da categoria; só texto de apresentação
     * @param eligibility      {@code ELIGIBLE}, {@code STALE} ou {@code NOT_ELIGIBLE}; nula se a série ainda não está
     *                         no snapshot
     * @param nextExpectedDate data esperada no mês do snapshot; nula se a série ainda não está nele
     * @param realizedThisMonth já ocorreu neste mês; nulo se a série ainda não está no snapshot
     * @param occurrences      ids de transação
     */
    public record ForecastRecurringSeriesDto(UUID id, String direction, String label,
                                             ForecastResponses.ForecastChannelDto channel, String periodicity,
                                             int expectedDay, MoneyDto expectedAmount,
                                             ForecastResponses.ForecastRangeDto amountRange,
                                             ForecastDetectedDto detected, boolean userAdjusted, String status,
                                             String origin, String eligibility, LocalDate nextExpectedDate,
                                             Boolean realizedThisMonth, List<UUID> occurrences,
                                             String algorithmVersion) {

        public static ForecastRecurringSeriesDto of(RecurringItem item) {
            RecurringSeries s = item.series();
            return new ForecastRecurringSeriesDto(item.id(), s.direction().name(), s.label(),
                    ForecastResponses.ForecastChannelDto.of(s.channel()), s.periodicity().name(), s.effectiveDay(),
                    ForecastResponses.money(s.effectiveAmount()),
                    ForecastResponses.range(s.effectiveRange()),
                    new ForecastDetectedDto(s.expectedDay(), ForecastResponses.money(s.expectedAmount()),
                            ForecastResponses.range(s.amountRange()), s.confidence().name()),
                    s.userExpectedAmount() != null || s.userExpectedDay() != null, s.status().name(),
                    s.origin().name(), item.eligibility() == null ? null : item.eligibility().name(),
                    item.expectedDate(), item.realizedThisMonth(), s.occurrences(), s.algorithmVersion());
        }
    }

    public record ForecastRecurringListResponse(List<ForecastRecurringSeriesDto> items) {

        public static ForecastRecurringListResponse of(List<RecurringItem> items) {
            return new ForecastRecurringListResponse(items.stream().map(ForecastRecurringSeriesDto::of).toList());
        }
    }

    /**
     * @param forecastState sempre {@code RECALCULATING}: o recálculo é assíncrono (ADR-0031 §5), e a elegibilidade
     *                      e a data esperada ainda são as do snapshot anterior
     */
    public record ForecastRecurringActionResponse(ForecastRecurringSeriesDto series, String forecastState) {

        public static ForecastRecurringActionResponse of(RecurringItem item) {
            return new ForecastRecurringActionResponse(ForecastRecurringSeriesDto.of(item), "RECALCULATING");
        }
    }
}
