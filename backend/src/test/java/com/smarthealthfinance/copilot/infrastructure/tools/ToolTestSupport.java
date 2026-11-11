package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures e verificações comuns dos testes das tools do Copilot. */
final class ToolTestSupport {

    static final UUID WORKSPACE = UUID.fromString("5d0c1a52-7e1f-4a53-9c33-0a5d1f0b7c01");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final Currency BRL = Currency.getInstance("BRL");
    static final Map<String, String> CATEGORIES = Map.of("FOOD", "Alimentação", "TRANSPORT", "Transporte");

    private ToolTestSupport() {
    }

    static Money brl(String amount) {
        return Money.of(amount, BRL);
    }

    static FinancialTool.Request request(IntentDecision decision, Pseudonyms pseudonyms) {
        return new FinancialTool.Request(WORKSPACE, decision, pseudonyms, TODAY, CATEGORIES);
    }

    static FinancialTool.Request request(Intent intent) {
        return request(IntentDecision.of(intent), new Pseudonyms());
    }

    static IntentDecision decision(Intent intent, YearMonth month, String category, String target,
                                   IntentDecision.Scenario scenario) {
        return new IntentDecision(intent, month, category, target, scenario);
    }

    /** O valor com o rótulo exato; falha se não existir. */
    static ContextValue value(FinancialTool.Result result, String label) {
        return result.values().stream().filter(v -> v.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("sem valor com o rótulo '" + label + "' em "
                        + result.values().stream().map(ContextValue::label).toList()));
    }

    static List<ContextValue> valuesWithLabelStarting(FinancialTool.Result result, String prefix) {
        return result.values().stream().filter(v -> v.label().startsWith(prefix)).toList();
    }

    /** Nenhum rótulo, valor, fonte ou ação contém algum dos textos proibidos (nome, apelido, final, UUID). */
    static void assertNoLeak(FinancialTool.Result result, String... forbidden) {
        StringBuilder everything = new StringBuilder();
        result.values().forEach(v -> everything.append(v.label()).append('|').append(v.value()).append('|')
                .append(v.period()).append('|').append(v.source()).append('\n'));
        result.sources().forEach(s -> everything.append(s).append('\n'));
        result.actions().forEach(a -> everything.append(a).append('\n'));
        for (String text : forbidden) {
            assertThat(everything.toString()).as("vazamento de '%s'", text).doesNotContainIgnoringCase(text);
        }
    }
}
