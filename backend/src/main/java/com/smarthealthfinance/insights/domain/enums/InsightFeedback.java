package com.smarthealthfinance.insights.domain.enums;

/** "Útil / Não útil" (ADR-0021 §8): "Não útil" tira 15 pontos de relevância do detector por 90 dias. */
public enum InsightFeedback {
    USEFUL,
    NOT_USEFUL
}
