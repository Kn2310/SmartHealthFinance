package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Próximo evento dos 30 dias (ADR-0030 §3.3): fato de fatura ou ocorrência estimada de série.
 *
 * @param key             chave da série; nula nos fatos de fatura
 * @param channel         conta da série ou cartão da fatura
 * @param value           {@code FACT} nos eventos de fatura, {@code ESTIMATED} nas séries
 * @param closingEstimate "≈ no fechamento" de uma fatura aberta (3.5), sem trocar o fato; nulo nos demais
 */
public record UpcomingEvent(UpcomingEventKind kind, LocalDate date, String key, String label, Channel channel,
                            Projected value, Projected closingEstimate) {

    public UpcomingEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(value, "value");
    }
}
