package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.health.domain.enums.HealthDimension;
import java.util.UUID;

/**
 * Sujeitos a que o insight se refere, para "Relacionados" e "Ver insight relacionado" (ADR-0021 §7). Todos opcionais.
 *
 * @param categoryCode  categoria de despesa (D1, D4) ou a maior alta (D2)
 * @param merchantKey   comerciante (D3; o maior comerciante em D1)
 * @param sourceKey     fonte de receita (D5, D6), como em {@code IncomeSources.sourceKey}
 * @param transactionId transação atípica (D4)
 * @param dimension     dimensão do Health afetada
 */
public record InsightRefs(String categoryCode, String merchantKey, String sourceKey, UUID transactionId,
                          HealthDimension dimension) {

    public static InsightRefs none() {
        return new InsightRefs(null, null, null, null, null);
    }
}
