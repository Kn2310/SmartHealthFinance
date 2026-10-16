package com.smarthealthfinance.insights.domain.enums;

/**
 * Filtro de destino de um insight (ADR-0021 §9). Receitas não têm chip próprio: aparecem só em Todos/Importantes, com
 * a tag "Receitas". Cartões chega com o M8.
 */
public enum InsightType {
    SPENDING,
    INCOME,
    HEALTH
}
