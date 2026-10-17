package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightFeedback;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import com.smarthealthfinance.shared.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/**
 * Pontuação (ADR-0021 §3): piso de impacto, Impact (I), Relevance (Rv), importância e prioridade. Pura: a relevância
 * olha só os insights já guardados do Workspace, recebidos como parâmetro.
 */
public final class InsightScoring {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Ordem da lista, da Home e de um futuro corte por plano (§3). */
    public static final Comparator<Insight> ORDER = Comparator
            .comparing((Insight i) -> i.importance().ordinal())
            .thenComparing(Comparator.comparingInt(Insight::priority).reversed())
            .thenComparing(i -> i.confidence().ordinal())
            .thenComparing(Comparator.comparing(Insight::updatedAt).reversed())
            .thenComparing(Insight::fingerprint);

    private InsightScoring() {
    }

    /**
     * Piso global: A ≥ R$ 50 e A ÷ R ≥ 1% (sem R, só o absoluto). D3 tem piso próprio e D7/D8 são em pontos.
     */
    public static boolean passesFloor(InsightCandidate candidate, Money reference) {
        InsightDetector detector = candidate.detector();
        if (detector == InsightDetector.NEW_RECURRING_MERCHANT || candidate.impactAmount() == null) {
            return true;
        }
        BigDecimal amount = candidate.impactAmount().amount();
        if (amount.compareTo(InsightRules.FLOOR_AMOUNT) < 0) {
            return false;
        }
        return reference == null || reference.amount().signum() == 0 || amount.multiply(HUNDRED)
                .compareTo(InsightRules.FLOOR_SHARE_PERCENT.multiply(reference.amount())) >= 0;
    }

    /** Pontos I (0–100): curva sobre A ÷ R, ou sobre os pontos do Health em D7/D8. */
    public static int impact(InsightCandidate candidate, Money reference) {
        if (candidate.impactPoints() != null) {
            return toScore(InsightRules.POINTS_CURVE.score(BigDecimal.valueOf(candidate.impactPoints())));
        }
        if (reference == null || reference.amount().signum() == 0) {
            return InsightRules.IMPACT_WITHOUT_REFERENCE;
        }
        BigDecimal share = candidate.impactAmount().amount().divide(reference.amount(), 4, RoundingMode.HALF_EVEN);
        return toScore(InsightRules.IMPACT_CURVE.score(share));
    }

    /**
     * Rv (0–100) = 60, +15 se o mesmo detector e sujeito dispararam no período anterior, −30 se algo igual foi
     * dispensado nos últimos 90 dias (outro fingerprint), −15 se houve "Não útil" no detector nos últimos 90 dias.
     *
     * @param history todos os insights guardados do Workspace (qualquer versão e estado)
     */
    public static int relevance(InsightCandidate candidate, String fingerprint, List<Insight> history, Instant now) {
        Instant memory = now.minus(Duration.ofDays(InsightRules.RELEVANCE_MEMORY_DAYS));
        int relevance = InsightRules.RELEVANCE_BASE;
        String previousPeriod = candidate.detector().periodBound() && candidate.periodKey() != null
                ? YearMonth.parse(candidate.periodKey()).minusMonths(1).toString() : null;
        boolean persistent = previousPeriod != null && history.stream().anyMatch(i -> sameSubject(i, candidate)
                && previousPeriod.equals(i.periodKey()));
        boolean dismissed = history.stream().anyMatch(i -> sameSubject(i, candidate)
                && i.status() == InsightStatus.DISMISSED && !i.fingerprint().equals(fingerprint)
                && !i.dismissedAt().isBefore(memory));
        boolean notUseful = history.stream().anyMatch(i -> i.detector() == candidate.detector()
                && i.feedback() == InsightFeedback.NOT_USEFUL && !i.feedbackAt().isBefore(memory));
        if (persistent) {
            relevance += InsightRules.RELEVANCE_PERSISTENT;
        }
        if (dismissed) {
            relevance += InsightRules.RELEVANCE_DISMISSED;
        }
        if (notUseful) {
            relevance += InsightRules.RELEVANCE_NOT_USEFUL;
        }
        return Math.max(0, Math.min(100, relevance));
    }

    public static ScoredCandidate score(InsightCandidate candidate, String fingerprint, Money reference,
                                        List<Insight> history, Instant now) {
        int impact = impact(candidate, reference);
        int relevance = relevance(candidate, fingerprint, history, now);
        boolean important = candidate.detector().canBeImportant() && candidate.importantByRule()
                && impact >= InsightRules.IMPORTANT_MIN_IMPACT && candidate.confidence() != Confidence.LOW
                && relevance >= InsightRules.IMPORTANT_MIN_RELEVANCE;
        int priority = BigDecimal.valueOf(impact).multiply(new BigDecimal("0.6"))
                .add(BigDecimal.valueOf(relevance).multiply(new BigDecimal("0.4")))
                .setScale(0, RoundingMode.HALF_EVEN).intValueExact();
        return new ScoredCandidate(candidate, fingerprint, impact, relevance,
                important ? Importance.IMPORTANT : Importance.INFORMATIVE, priority);
    }

    private static boolean sameSubject(Insight insight, InsightCandidate candidate) {
        return insight.detector() == candidate.detector() && insight.subject().equals(candidate.subject());
    }

    private static int toScore(BigDecimal value) {
        return Math.max(0, Math.min(100, value.setScale(0, RoundingMode.HALF_EVEN).intValueExact()));
    }
}
