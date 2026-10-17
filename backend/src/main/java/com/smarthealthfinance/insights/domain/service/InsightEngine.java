package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.shared.domain.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Catálogo {@code insights-v1} (ADR-0021 §2): roda os 10 detectores sobre a mesma entrada e aplica o piso de impacto
 * (§3). Puro e determinístico: mesma entrada, mesmos candidatos, na mesma ordem.
 */
public final class InsightEngine {

    private InsightEngine() {
    }

    public static List<InsightCandidate> detect(InsightInput input) {
        if (input.firstActivity().isEmpty()) {
            return List.of();
        }
        DetectionContext ctx = new DetectionContext(input);
        List<InsightCandidate> all = new ArrayList<>();
        all.addAll(SpendingDetectors.categorySpikes(ctx));
        all.addAll(SpendingDetectors.spendingAbovePattern(ctx));
        all.addAll(MerchantDetectors.newRecurringMerchants(ctx));
        all.addAll(MerchantDetectors.unusualExpenses(ctx));
        all.addAll(IncomeDetectors.recurringIncome(ctx));
        all.addAll(IncomeDetectors.newIncomeSources(ctx));
        all.addAll(HealthDetectors.scoreChange(ctx));
        all.addAll(HealthDetectors.dimensionChange(ctx));
        all.addAll(HealthDetectors.lowReserve(ctx));
        all.addAll(SpendingDetectors.uncategorizedShare(ctx));
        return all.stream()
                .filter(c -> InsightScoring.passesFloor(c, ctx.reference()))
                .sorted(Comparator.comparing((InsightCandidate c) -> c.detector().ordinal())
                        .thenComparing(InsightCandidate::subject))
                .toList();
    }

    /** R da entrada (despesa média da janela W), usado no impacto relativo. */
    public static Money reference(InsightInput input) {
        return input.firstActivity().isEmpty() ? null : new DetectionContext(input).reference();
    }
}
