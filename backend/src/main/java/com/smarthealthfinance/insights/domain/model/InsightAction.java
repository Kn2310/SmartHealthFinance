package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.insights.domain.enums.ActionKind;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Ação do insight (ADR-0021 §2): código e parâmetros (ex.: {@code month}, {@code category}, {@code from}/{@code to},
 * {@code transactionId}); a rota é montada pelo frontend.
 *
 * @param available falso nos placeholders "Em breve" (objetivo, Copilot)
 * @param params    em ordem de chave, para a gravação ser determinística
 */
public record InsightAction(ActionKind kind, String label, boolean primary, boolean available,
                            Map<String, String> params) {

    public InsightAction {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        params = Collections.unmodifiableSortedMap(new TreeMap<>(params));
    }

    public static InsightAction primary(ActionKind kind, String label, Map<String, String> params) {
        return new InsightAction(kind, label, true, true, params);
    }

    public static InsightAction secondary(ActionKind kind, String label, Map<String, String> params) {
        return new InsightAction(kind, label, false, true, params);
    }

    public static InsightAction comingSoon(ActionKind kind, String label) {
        return new InsightAction(kind, label, false, false, Map.of());
    }
}
