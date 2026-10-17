package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.health.domain.service.HealthTexts;
import com.smarthealthfinance.shared.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

/**
 * Formatação pt-BR dos números dos templates (ADR-0021 §4). Determinística: não depende do locale da máquina. Os
 * textos nunca saem em log.
 */
public final class InsightTexts {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final String[] MONTHS = {"janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho",
            "agosto", "setembro", "outubro", "novembro", "dezembro"};
    private static final String[] SHORT_MONTHS = {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out",
            "nov", "dez"};
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private InsightTexts() {
    }

    /** Valor exato: {@code R$ 1.284,00}. */
    public static String money(Money value) {
        return HealthTexts.money(value);
    }

    /**
     * Valor aproximado, para "cerca de R$ {v}" (ADR-0021 §4): múltiplo de 10 abaixo de R$ 1.000, de 50 de R$ 1.000 a
     * R$ 9.999,99 e de 100 a partir de R$ 10.000, HALF_EVEN, sem centavos: {@code R$ 280}, {@code R$ 1.250}.
     */
    public static String approx(Money value) {
        BigDecimal amount = value.amount().abs();
        BigDecimal step = amount.compareTo(THOUSAND) < 0 ? BigDecimal.TEN
                : amount.compareTo(TEN_THOUSAND) < 0 ? BigDecimal.valueOf(50) : HUNDRED;
        BigDecimal rounded = amount.divide(step, 0, RoundingMode.HALF_EVEN).multiply(step);
        String symbol = "BRL".equals(value.currency().getCurrencyCode()) ? "R$" : value.currency().getCurrencyCode();
        return symbol + " " + decimal("#,##0").format(rounded);
    }

    /** Percentual inteiro da razão exata {@code part ÷ whole × 100}, HALF_EVEN, sem o símbolo: {@code 32}. */
    public static String percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(HUNDRED).divide(whole, 0, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Uma casa, vírgula decimal: {@code 2,4}. */
    public static String oneDecimal(BigDecimal value) {
        return decimal("0.0").format(value.setScale(1, RoundingMode.HALF_EVEN));
    }

    /** {@code setembro}. */
    public static String monthName(YearMonth month) {
        return MONTHS[month.getMonthValue() - 1];
    }

    /** {@code Setembro}. */
    public static String capitalizedMonthName(YearMonth month) {
        String name = monthName(month);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** {@code abr–ago}; anos diferentes: {@code nov/2025–mar/2026}; um mês: {@code ago}. */
    public static String range(List<YearMonth> months) {
        YearMonth first = months.getFirst();
        YearMonth last = months.getLast();
        if (first.equals(last)) {
            return shortMonth(first);
        }
        if (first.getYear() == last.getYear()) {
            return shortMonth(first) + "–" + shortMonth(last);
        }
        return shortMonth(first) + "/" + first.getYear() + "–" + shortMonth(last) + "/" + last.getYear();
    }

    /** {@code 22 set}. */
    public static String dayMonth(LocalDate day) {
        return day.getDayOfMonth() + " " + shortMonth(YearMonth.from(day));
    }

    /** "neste mês" no período em andamento; "em setembro" no fechado. */
    public static String inPeriod(YearMonth month, boolean partial) {
        return partial ? "neste mês" : "em " + monthName(month);
    }

    /** Nome no meio da frase: primeira letra minúscula ({@code Alimentação fora} → {@code alimentação fora}). */
    public static String lowerFirst(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Início de rótulo: primeira letra maiúscula ({@code salário} → {@code Salário}). */
    public static String capitalizeFirst(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** {@code 1 transação}, {@code 25 transações}. */
    public static String count(int value, String singular, String plural) {
        return value + " " + (value == 1 ? singular : plural);
    }

    private static String shortMonth(YearMonth month) {
        return SHORT_MONTHS[month.getMonthValue() - 1];
    }

    private static DecimalFormat decimal(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(PT_BR));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format;
    }
}
