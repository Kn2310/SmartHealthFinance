package com.smarthealthfinance.insights.domain.enums;

/**
 * Filtro de destino de um insight (ADR-0021 §9). Receitas não têm chip próprio: aparecem só em Todos/Importantes, com
 * a tag "Receitas". Cartões chegou com a {@code insights-v2} (ADR-0028).
 */
public enum InsightType {
    SPENDING,
    INCOME,
    HEALTH,
    CARDS
}
