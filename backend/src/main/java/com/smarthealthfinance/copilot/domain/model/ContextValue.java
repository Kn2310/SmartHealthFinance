package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Um valor do contexto {@code context-v1} (ADR-0039 §5): calculado por uma tool ou pela etapa Deterministic
 * Calculations, nunca pelo modelo. O modelo cita pelo {@code ref} ({@code {{v:ref}}}); o sistema renderiza.
 *
 * <p>{@code label} é texto do sistema e não pode ter dígito fora de um pseudônimo ({@code cartao_1}): um rótulo com
 * número seria copiado pelo modelo e reprovaria a validação. {@code period} é {@code YYYY-MM}, {@code YYYY-MM-DD} ou
 * um intervalo {@code de..até}, ou nulo. {@code source} é a tool e a versão do algoritmo (ex.: {@code health-v2}).
 */
public record ContextValue(String ref, String label, String value, ValueUnit unit, ValueKind kind, String period,
                           String source) {

    public static final Pattern REF = Pattern.compile("v[1-9]\\d{0,3}");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern PERIOD = Pattern.compile("\\d{4}-\\d{2}(-\\d{2})?(\\.\\.\\d{4}-\\d{2}(-\\d{2})?)?");

    public ContextValue {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        if (!REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("invalid ref");
        }
        if (label.isBlank() || Pseudonyms.withoutPseudonyms(label).chars().anyMatch(Character::isDigit)) {
            throw new IllegalArgumentException("label must be non-blank and digit-free");
        }
        if (period != null && !PERIOD.matcher(period).matches()) {
            throw new IllegalArgumentException("invalid period");
        }
        requireFormat(unit, value);
    }

    /** Valor ainda sem ref: a tool produz, o Context Builder numera. */
    public static ContextValue draft(String label, String value, ValueUnit unit, ValueKind kind, String period,
                                     String source) {
        return new ContextValue("v1", label, value, unit, kind, period, source);
    }

    public ContextValue withRef(String newRef) {
        return new ContextValue(newRef, label, value, unit, kind, period, source);
    }

    /** Sem rótulo nem valor: dados financeiros não vão para logs. */
    @Override
    public String toString() {
        return "ContextValue[" + ref + ", " + unit + ", " + kind + "]";
    }

    public boolean labelled() {
        return kind != ValueKind.FACT;
    }

    private static void requireFormat(ValueUnit unit, String value) {
        boolean valid = switch (unit) {
            case MONEY, PERCENT -> DECIMAL.matcher(value).matches();
            case SCORE, COUNT -> INTEGER.matcher(value).matches();
            case MONTH -> parses(() -> YearMonth.parse(value));
            case DATE -> parses(() -> LocalDate.parse(value));
            case TEXT -> !value.isBlank() && value.length() <= 120;
        };
        if (!valid) {
            throw new IllegalArgumentException("value does not match unit " + unit);
        }
    }

    private static boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Dinheiro e percentual como decimal; inteiros como estão. Só para MONEY/PERCENT/SCORE/COUNT. */
    public BigDecimal decimal() {
        return new BigDecimal(value);
    }
}
