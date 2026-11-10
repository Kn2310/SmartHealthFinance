package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.enums.AnswerConfidence;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.DataQuality;

/**
 * Regra {@value #VERSION} (ADR-0039 §6): a confiança da resposta vem só da qualidade dos dados, nunca do modelo.
 * <ul>
 *   <li>{@code LOW}: read model não construído, ou menos de 2 meses com dados;</li>
 *   <li>{@code MEDIUM}: read model atualizando, período parcial, lacunas, menos de 3 meses com dados, ou valores
 *       estimados ou simulados;</li>
 *   <li>{@code HIGH}: o resto.</li>
 * </ul>
 * Meses com dados só contam quando se aplicam à intenção.
 */
public final class ConfidencePolicy {

    public static final String VERSION = "confidence-v1";

    private ConfidencePolicy() {
    }

    public static AnswerConfidence of(CopilotContext context) {
        DataQuality quality = context.quality();
        Integer months = quality.monthsWithData();
        if (quality.freshness() == DataFreshness.NOT_BUILT || (months != null && months < 2)) {
            return AnswerConfidence.LOW;
        }
        if (quality.freshness() == DataFreshness.UPDATING || quality.partialPeriod() || !quality.gaps().isEmpty()
                || (months != null && months < 3) || context.has(ValueKind.ESTIMATED)
                || context.has(ValueKind.SIMULATED)) {
            return AnswerConfidence.MEDIUM;
        }
        return AnswerConfidence.HIGH;
    }
}
