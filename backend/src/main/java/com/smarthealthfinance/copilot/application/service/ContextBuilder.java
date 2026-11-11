package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Context Builder e Context Validation (ADR-0039 §5): numera os valores da tool ({@code v1}, {@code v2}…) na ordem de
 * prioridade e corta pelo fim quando o contexto passa do teto de tokens estimados. O corte é sempre de valores
 * inteiros, nunca no meio de um.
 */
final class ContextBuilder {

    /** Custo fixo estimado de cada valor no JSON (chaves, aspas, unidade e tipo), em caracteres. */
    private static final int VALUE_OVERHEAD_CHARS = 90;

    private ContextBuilder() {
    }

    static CopilotContext build(Intent intent, LocalDate today, Currency currency, FinancialTool.Result result,
                                int tokenCeiling) {
        List<ContextValue> values = new ArrayList<>();
        long chars = 0;
        for (ContextValue value : result.values()) {
            chars += VALUE_OVERHEAD_CHARS + value.label().length() + value.value().length() + value.source().length()
                    + (value.period() == null ? 0 : value.period().length());
            if ((chars + 3) / 4 > tokenCeiling) {
                break;
            }
            values.add(value.withRef("v" + (values.size() + 1)));
        }
        return new CopilotContext(intent, today, currency, values, result.quality(), result.sources(),
                result.actions());
    }
}
