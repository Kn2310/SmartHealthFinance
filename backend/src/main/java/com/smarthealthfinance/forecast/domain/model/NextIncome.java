package com.smarthealthfinance.forecast.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Próxima receita prevista (ADR-0030 §3.2): sempre {@code ESTIMATED}, com valor esperado, faixa, data e fonte.
 *
 * @param key chave da série (a fonte)
 */
public record NextIncome(String key, String label, LocalDate date, Channel channel, Projected amount) {

    public NextIncome {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(amount, "amount");
    }
}
