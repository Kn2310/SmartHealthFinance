package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.model.ContextValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.Locale;

/**
 * Formata um valor do contexto em pt-BR (ADR-0039 §7.2). Sem arredondamento novo: dinheiro sai com as casas da moeda,
 * como nas telas, e percentual com a escala que o backend calculou. Estimados e simulados ganham o rótulo entre
 * parênteses.
 */
public final class ValueFormatter {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final String[] MONTHS = {"janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho",
            "agosto", "setembro", "outubro", "novembro", "dezembro"};

    private ValueFormatter() {
    }

    public static String format(ContextValue value, Currency currency) {
        String text = switch (value.unit()) {
            case MONEY -> money(value.decimal(), currency);
            case PERCENT -> number(value.decimal(), Math.max(0, value.decimal().scale())) + "%";
            case SCORE, COUNT -> number(value.decimal(), 0);
            case MONTH -> month(YearMonth.parse(value.value()));
            case DATE -> date(LocalDate.parse(value.value()));
            case TEXT -> value.value();
        };
        return value.labelled() ? text + " (" + value.kind().label() + ")" : text;
    }

    /**
     * {@code R$ 1.234,56}; negativo com sinal: {@code -R$ 120,00}. Casas da moeda com HALF_EVEN, o mesmo das telas
     * (o valor armazenado tem 4 casas).
     */
    static String money(BigDecimal amount, Currency currency) {
        int scale = Math.max(0, currency.getDefaultFractionDigits());
        BigDecimal rounded = amount.setScale(scale, RoundingMode.HALF_EVEN);
        String symbol = "BRL".equals(currency.getCurrencyCode()) ? "R$" : currency.getCurrencyCode();
        return (rounded.signum() < 0 ? "-" : "") + symbol + " " + number(rounded.abs(), scale);
    }

    static String month(YearMonth month) {
        return MONTHS[month.getMonthValue() - 1] + " de " + month.getYear();
    }

    static String date(LocalDate date) {
        return "%02d/%02d/%d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private static String number(BigDecimal value, int scale) {
        DecimalFormat format = new DecimalFormat(scale == 0 ? "#,##0" : "#,##0." + "0".repeat(scale),
                DecimalFormatSymbols.getInstance(PT_BR));
        format.setParseBigDecimal(true);
        return format.format(value);
    }
}
