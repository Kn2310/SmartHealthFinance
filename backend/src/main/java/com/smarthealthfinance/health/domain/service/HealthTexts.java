package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.shared.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

/**
 * Formatação em pt-BR dos números que entram nos templates (ADR-0017 §7). Determinística: não depende do locale da
 * máquina. Os textos nunca saem em log.
 */
public final class HealthTexts {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final String[] MONTHS = {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out",
            "nov", "dez"};

    private HealthTexts() {
    }

    /** {@code R$ 2.368,00}; negativo com sinal: {@code -R$ 120,00}. */
    public static String money(Money value) {
        BigDecimal amount = value.amount().setScale(2, RoundingMode.HALF_EVEN);
        String digits = decimal("#,##0.00").format(amount.abs());
        String symbol = "BRL".equals(value.currency().getCurrencyCode()) ? "R$" : value.currency().getCurrencyCode();
        return (amount.signum() < 0 ? "-" : "") + symbol + " " + digits;
    }

    /** Percentual com 1 casa: {@code 36,1%}. */
    public static String percent(BigDecimal percent) {
        return decimal("0.0").format(percent.setScale(1, RoundingMode.HALF_EVEN)) + "%";
    }

    /** Percentual com sinal: {@code +12,3%}, {@code -4,0%}, {@code 0,0%}. */
    public static String signedPercent(BigDecimal percent) {
        BigDecimal rounded = percent.setScale(1, RoundingMode.HALF_EVEN);
        return (rounded.signum() > 0 ? "+" : "") + percent(rounded);
    }

    /** Meses de cobertura com 1 casa: {@code 2,4}. */
    public static String oneDecimal(BigDecimal value) {
        return decimal("0.0").format(value.setScale(1, RoundingMode.HALF_EVEN));
    }

    /** {@code set/2026}. */
    public static String month(YearMonth month) {
        return MONTHS[month.getMonthValue() - 1] + "/" + month.getYear();
    }

    /** {@code abr/2026 a set/2026}, ou um mês só. */
    public static String months(List<YearMonth> months) {
        if (months.size() == 1) {
            return month(months.getFirst());
        }
        return month(months.getFirst()) + " a " + month(months.getLast());
    }

    /** Ratio 0–1 em percentual (×100). */
    public static BigDecimal toPercent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100));
    }

    private static DecimalFormat decimal(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(PT_BR));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        format.setParseBigDecimal(true);
        return format;
    }
}
