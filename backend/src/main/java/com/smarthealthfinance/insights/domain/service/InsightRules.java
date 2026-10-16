package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.health.domain.valueobject.ScoreCurve;
import java.math.BigDecimal;

/**
 * Limiares do catálogo {@code insights-v1} (ADR-0021 §2/§3), num lugar só: mudar qualquer um exige nova versão
 * ({@link #ALGORITHM_VERSION}, §6). Valores monetários na moeda base do Workspace.
 */
public final class InsightRules {

    public static final String ALGORITHM_VERSION = "insights-v1";

    /** Meses da média de comparação (ADR-0015 §2) e mínimo de meses usados pelos detectores comparativos. */
    public static final int AVERAGE_MONTHS = 5;
    public static final int MIN_AVERAGE_MONTHS = 2;

    // D1 — categoria acima da média.
    public static final BigDecimal CATEGORY_MIN_DELTA = new BigDecimal("100");
    public static final BigDecimal CATEGORY_MIN_PERCENT = new BigDecimal("20");
    public static final BigDecimal CATEGORY_MIN_SHARE_OF_REFERENCE = new BigDecimal("3");
    public static final int CATEGORY_MAX = 3;
    /** Participação a partir da qual o maior comerciante aparece no apoio e na ação recomendada. */
    public static final BigDecimal TOP_MERCHANT_SHARE = new BigDecimal("30");

    // D2 — gasto total acima do padrão.
    public static final BigDecimal TOTAL_MIN_DELTA = new BigDecimal("200");
    public static final BigDecimal TOTAL_MIN_PERCENT = new BigDecimal("15");

    // D3 — comerciante recorrente novo.
    public static final int RECURRING_MIN_MONTHS = 3;
    public static final int RECURRING_ABSENT_MONTHS = 3;
    public static final BigDecimal RECURRING_TOLERANCE_PERCENT = new BigDecimal("10");
    public static final BigDecimal RECURRING_MIN_AMOUNT = new BigDecimal("10");

    // D4 — compra atípica.
    public static final int UNUSUAL_CANDIDATES = 10;
    public static final BigDecimal UNUSUAL_MIN_AMOUNT = new BigDecimal("250");
    public static final BigDecimal UNUSUAL_MIN_MULTIPLE = new BigDecimal("3");
    public static final int UNUSUAL_HISTORY_MONTHS = 6;
    public static final int UNUSUAL_MIN_PURCHASES = 5;
    public static final int UNUSUAL_MAX = 3;

    // D5 — receita recorrente ausente ou menor.
    public static final int INCOME_GRACE_DAYS = 5;
    public static final BigDecimal INCOME_MIN_EXPECTED = new BigDecimal("200");
    public static final BigDecimal INCOME_LOWER_PERCENT = new BigDecimal("80");
    public static final BigDecimal INCOME_MIN_SHORTFALL = new BigDecimal("200");

    // D6 — nova fonte de receita.
    public static final int NEW_SOURCE_LOOKBACK_MONTHS = 6;
    public static final int NEW_SOURCE_MIN_HISTORY_MONTHS = 3;
    public static final BigDecimal NEW_SOURCE_MIN_AMOUNT = new BigDecimal("100");

    // D7/D8 — Health.
    public static final int SCORE_MIN_CHANGE = 5;
    public static final int SCORE_IMPORTANT_CHANGE = 10;
    public static final int DIMENSION_MIN_CHANGE = 10;

    // D9 — reserva.
    public static final BigDecimal RESERVE_MAX_COVERAGE = new BigDecimal("3");
    public static final BigDecimal RESERVE_IMPORTANT_COVERAGE = BigDecimal.ONE;
    public static final BigDecimal RESERVE_REFERENCE_MONTHS = new BigDecimal("6");

    // D10 — gastos sem categoria.
    public static final BigDecimal UNCATEGORIZED_MIN_PERCENT = new BigDecimal("15");
    public static final BigDecimal UNCATEGORIZED_MIN_AMOUNT = new BigDecimal("200");

    // Pontuação (§3).
    public static final BigDecimal FLOOR_AMOUNT = new BigDecimal("50");
    public static final BigDecimal FLOOR_SHARE_PERCENT = BigDecimal.ONE;
    /** Pontos I sobre s = A ÷ R (razão). */
    public static final ScoreCurve IMPACT_CURVE = ScoreCurve.of("0", "0", "0.02", "20", "0.05", "50", "0.10", "80",
            "0.20", "100");
    /** Pontos I sobre a variação do Health, em pontos (D7/D8). */
    public static final ScoreCurve POINTS_CURVE = ScoreCurve.of("0", "0", "5", "40", "10", "70", "20", "100");
    /** I quando não há despesa média de referência (R = 0). */
    public static final int IMPACT_WITHOUT_REFERENCE = 20;
    public static final int IMPORTANT_MIN_IMPACT = 50;
    public static final int IMPORTANT_MIN_RELEVANCE = 50;
    public static final int RELEVANCE_BASE = 60;
    public static final int RELEVANCE_PERSISTENT = 15;
    public static final int RELEVANCE_DISMISSED = -30;
    public static final int RELEVANCE_NOT_USEFUL = -15;
    public static final int RELEVANCE_MEMORY_DAYS = 90;

    // Confiança (§3).
    public static final int CONFIDENCE_HIGH_MONTHS = 5;
    public static final int CONFIDENCE_MEDIUM_MONTHS = 3;
    public static final BigDecimal CONFIDENCE_HIGH_UNCATEGORIZED = new BigDecimal("0.10");
    public static final BigDecimal CONFIDENCE_MEDIUM_UNCATEGORIZED = new BigDecimal("0.25");
    /** No mês em andamento, confiança alta só a partir deste dia. */
    public static final int CONFIDENCE_HIGH_FROM_DAY = 15;

    private InsightRules() {
    }
}
