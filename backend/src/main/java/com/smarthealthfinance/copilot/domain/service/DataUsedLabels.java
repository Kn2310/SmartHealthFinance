package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.DataUsedItem;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * "Dados usados" da resposta (ADR-0040 §3): um rótulo legível pt-BR montado a partir da intenção e dos períodos dos
 * valores do contexto (ex.: "Fatura out/2026", "Fluxo de caixa jun/2026 a nov/2026"), com {@code partial} quando o
 * período é parcial. Nunca vem do modelo.
 */
public final class DataUsedLabels {

    private static final String[] MONTHS = {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out",
            "nov", "dez"};

    private DataUsedLabels() {
    }

    public static List<DataUsedItem> of(CopilotContext context) {
        TreeSet<YearMonth> months = new TreeSet<>();
        for (ContextValue value : context.values()) {
            if (value.period() != null) {
                for (String part : value.period().split("\\.\\.")) {
                    month(part).ifPresent(months::add);
                }
            }
        }
        String label = noun(context.intent());
        if (!months.isEmpty()) {
            label += " " + format(months.first()) + (months.size() > 1 ? " a " + format(months.last()) : "");
        }
        return List.of(new DataUsedItem(label, context.quality().partialPeriod()));
    }

    /** {@code out/2026}. */
    public static String format(YearMonth month) {
        return MONTHS[month.getMonthValue() - 1] + "/" + month.getYear();
    }

    private static Optional<YearMonth> month(String period) {
        try {
            return Optional.of(YearMonth.parse(period.substring(0, Math.min(7, period.length()))));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String noun(Intent intent) {
        return switch (intent) {
            case OVERVIEW -> "Visão geral";
            case CASH_FLOW -> "Fluxo de caixa";
            case CATEGORY -> "Gastos por categoria";
            case MERCHANT -> "Gastos por comerciante";
            case HEALTH -> "Saúde financeira";
            case INSIGHTS -> "Insights";
            case CARDS -> "Fatura";
            case FORECAST -> "Previsão";
            case GOALS -> "Objetivos";
            case GOAL_SIMULATION -> "Simulação de objetivo";
            case OUT_OF_SCOPE, UNSUPPORTED_ACTION -> "Dados";
        };
    }
}
