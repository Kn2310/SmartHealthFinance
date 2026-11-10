package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;

import java.time.LocalDate;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Contexto {@value #VERSION} de uma pergunta (ADR-0039 §5): só valores calculados pelo sistema, já minimizados e com
 * pseudônimos. É a única fonte de números da resposta (§7). Refs são únicas.
 */
public record CopilotContext(Intent intent, LocalDate today, Currency currency, List<ContextValue> values,
                             DataQuality quality, List<SourceRef> sources, List<OfferedAction> actions) {

    public static final String VERSION = "context-v1";

    public CopilotContext {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(quality, "quality");
        values = List.copyOf(values);
        sources = List.copyOf(sources);
        actions = List.copyOf(actions);
        Set<String> refs = new HashSet<>();
        for (ContextValue value : values) {
            if (!refs.add(value.ref())) {
                throw new IllegalArgumentException("duplicated ref " + value.ref());
            }
        }
    }

    /** Sem valores: dados financeiros não vão para logs. */
    @Override
    public String toString() {
        return "CopilotContext[" + intent + ", " + values.size() + " values]";
    }

    public Optional<ContextValue> find(String ref) {
        return values.stream().filter(value -> value.ref().equals(ref)).findFirst();
    }

    public Map<String, ContextValue> byRef() {
        Map<String, ContextValue> map = new LinkedHashMap<>();
        values.forEach(value -> map.put(value.ref(), value));
        return map;
    }

    public boolean has(ValueKind kind) {
        return values.stream().anyMatch(value -> value.kind() == kind);
    }

    public boolean empty() {
        return values.isEmpty();
    }
}
