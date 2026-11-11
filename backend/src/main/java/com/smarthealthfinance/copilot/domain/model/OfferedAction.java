package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;

import java.util.Map;
import java.util.Objects;

/**
 * Link que o contexto oferece ao modelo (ADR-0039 §6): o modelo só pode sugerir ações desta lista. {@code target} é
 * um pseudônimo, ou nulo para telas do Workspace inteiro. {@code params} são parâmetros da tela montados pelo sistema
 * (ex.: {@code month} em {@code OPEN_INVOICE}); nunca vão ao modelo.
 */
public record OfferedAction(ActionKind kind, String target, Map<String, String> params) {

    public OfferedAction {
        Objects.requireNonNull(kind, "kind");
        if (kind.needsTarget() == (target == null)) {
            throw new IllegalArgumentException("target is required exactly for item actions");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public OfferedAction(ActionKind kind, String target) {
        this(kind, target, Map.of());
    }

    /** A fatura do mês de vencimento {@code month} ({@code YYYY-MM}). */
    public static OfferedAction invoice(String cardPseudonym, String month) {
        return new OfferedAction(ActionKind.OPEN_INVOICE, cardPseudonym,
                month == null ? Map.of() : Map.of("month", month));
    }
}
