package com.smarthealthfinance.insights.domain.enums;

/**
 * Catálogo (ADR-0021 §2; D11, D12 e D14 de Cartões na {@code insights-v2}, ADR-0028), com o tipo (filtro), se o
 * detector pode gerar insight Importante (§3) e se o insight é preso a um período avaliado (D3 usa o mês de início da
 * série; D9 e D12 não têm período, §5) ou a uma fatura (D11, D14: o período é o mês de vencimento).
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
    UNCATEGORIZED_SHARE(InsightType.SPENDING, false, true),
    INVOICE_ABOVE_PATTERN(InsightType.CARDS, true, false, true),
    CARD_UTILIZATION_HIGH(InsightType.CARDS, true, false),
    INVOICE_DUE(InsightType.CARDS, true, false, true);

    private final InsightType type;
    private final boolean canBeImportant;
    private final boolean periodBound;
    private final boolean invoiceBound;

    InsightDetector(InsightType type, boolean canBeImportant, boolean periodBound) {
        this(type, canBeImportant, periodBound, false);
    }

    InsightDetector(InsightType type, boolean canBeImportant, boolean periodBound, boolean invoiceBound) {
        this.type = type;
        this.canBeImportant = canBeImportant;
        this.periodBound = periodBound;
        this.invoiceBound = invoiceBound;
    }

    public InsightType type() {
        return type;
    }

    /** Elegível a Importante; alguns só sob condição extra (D7 com |variação| ≥ 10, D9 com c &lt; 1). */
    public boolean canBeImportant() {
        return canBeImportant;
    }

    /** Expira quando o período avaliado muda; D3, D9 e D12 resolvem quando a condição deixa de valer. */
    public boolean periodBound() {
        return periodBound;
    }

    /**
     * Preso a uma fatura (D11, D14): o período é o mês de vencimento e o sujeito começa pelo cartão. Não detectado,
     * resolve enquanto a fatura está em avaliação e expira depois (ADR-0028 §5).
     */
    public boolean invoiceBound() {
        return invoiceBound;
    }
}
