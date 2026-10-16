package com.smarthealthfinance.insights.domain.enums;

/**
 * Catálogo {@code insights-v1} (ADR-0021 §2), com o tipo (filtro), se o detector pode gerar insight Importante
 * (§3) e se o insight é preso a um período avaliado (D3 usa o mês de início da série; D9 não tem período, §5).
 */
public enum InsightDetector {

    CATEGORY_SPIKE(InsightType.SPENDING, true, true),
    SPENDING_ABOVE_PATTERN(InsightType.SPENDING, true, true),
    NEW_RECURRING_MERCHANT(InsightType.SPENDING, false, false),
    UNUSUAL_EXPENSE(InsightType.SPENDING, true, true),
    RECURRING_INCOME_MISSING(InsightType.INCOME, true, true),
    RECURRING_INCOME_LOWER(InsightType.INCOME, true, true),
    NEW_INCOME_SOURCE(InsightType.INCOME, false, true),
    HEALTH_SCORE_CHANGE(InsightType.HEALTH, true, true),
    HEALTH_DIMENSION_CHANGE(InsightType.HEALTH, false, true),
    LOW_RESERVE(InsightType.HEALTH, true, false),
    UNCATEGORIZED_SHARE(InsightType.SPENDING, false, true);

    private final InsightType type;
    private final boolean canBeImportant;
    private final boolean periodBound;

    InsightDetector(InsightType type, boolean canBeImportant, boolean periodBound) {
        this.type = type;
        this.canBeImportant = canBeImportant;
        this.periodBound = periodBound;
    }

    public InsightType type() {
        return type;
    }

    /** Elegível a Importante; alguns só sob condição extra (D7 com |variação| ≥ 10, D9 com c &lt; 1). */
    public boolean canBeImportant() {
        return canBeImportant;
    }

    /** Expira quando o período avaliado muda; D3 e D9 resolvem quando a condição deixa de valer. */
    public boolean periodBound() {
        return periodBound;
    }
}
