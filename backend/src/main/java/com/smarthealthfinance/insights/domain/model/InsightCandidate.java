package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.shared.domain.Money;
import java.util.Objects;

/**
 * O que um detector encontrou (ADR-0021 §2), antes da pontuação e da reconciliação.
 *
 * @param periodKey      {@code YYYY-MM}: o período avaliado; em D3 o mês de início da série; nulo em D9
 * @param subject        sujeito da identidade (§5): categoria, comerciante, transação, fonte, dimensão…
 * @param impactAmount   impacto A em dinheiro; nulo em D7/D8
 * @param impactPoints   impacto em pontos do score (D7/D8); nulo nos demais
 * @param importantByRule condição extra de elegibilidade (D7 com |variação| ≥ 10, D9 com c &lt; 1); verdadeira nos
 *                        demais
 * @param partial        o período avaliado ainda está em andamento
 */
public record InsightCandidate(InsightDetector detector, String periodKey, String subject, Money impactAmount,
                               Integer impactPoints, boolean importantByRule, Confidence confidence, boolean partial,
                               InsightContent content) {

    public InsightCandidate {
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(content, "content");
        if ((impactAmount == null) == (impactPoints == null)) {
            throw new IllegalArgumentException("O impacto é em dinheiro ou em pontos, nunca os dois");
        }
        if (detector.periodBound() && periodKey == null) {
            throw new IllegalArgumentException("Detector preso a período precisa da chave do período");
        }
    }
}
