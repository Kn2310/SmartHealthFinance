package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightFeedback;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pontuação (ADR-0021 §3): piso, Impact, Relevance, importância e prioridade. */
class InsightScoringTest {

    static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    static final UUID WORKSPACE = UUID.fromString("00000000-0000-7000-8000-0000000000aa");

    static InsightContent content(String title) {
        return new InsightContent(title, "apoio", null, new InsightEvidence(List.of(), List.of(), List.of(), "como",
                List.of(), List.of()), List.of(InsightAction.primary(ActionKind.VIEW_HEALTH, "Ver saúde", Map.of())),
                InsightRefs.none());
    }

    static InsightCandidate money(InsightDetector detector, String period, String subject, String amount,
                                  Confidence confidence) {
        return new InsightCandidate(detector, period, subject, Money.of(amount, InsightFixture.BRL), null, true,
                confidence, true, content(detector + " " + subject));
    }

    static Insight stored(InsightDetector detector, String period, String subject, InsightStatus status) {
        ScoredCandidate scored = InsightScoring.score(money(detector, period, subject, "500.00", Confidence.HIGH),
                InsightFingerprint.of(detector, WORKSPACE, period, subject), null, List.of(), NOW);
        Insight insight = Insight.create(UUID.randomUUID(), WORKSPACE, InsightRules.ALGORITHM_VERSION, scored,
                NOW.minus(Duration.ofDays(10)));
        return switch (status) {
            case DISMISSED -> new Insight(insight.id(), insight.workspaceId(), insight.fingerprint(),
                    insight.algorithmVersion(), detector, period, subject, InsightStatus.DISMISSED, null,
                    insight.importance(), insight.impact(), insight.relevance(), insight.confidence(),
                    insight.priority(), insight.impactAmount(), null, true, insight.content(), null,
                    NOW.minus(Duration.ofDays(10)), null, null, 1, insight.createdAt(), insight.updatedAt(),
                    insight.statusChangedAt());
            default -> insight.withStatus(status, null, insight.createdAt());
        };
    }

    @ParameterizedTest(name = "A {0}, R {1} → {2}")
    @CsvSource(nullValues = "-", value = {
            "50.00, 4192.00, true",
            "49.99, -, false",
            "50.00, -, true",
            "99.99, 10000.00, false",
            "100.00, 10000.00, true",
            "50.00, 0, true"})
    void floorIsFiftyAndOnePercentOfTheReference(String amount, String reference, boolean passes) {
        Money r = reference == null ? null : Money.of(reference, InsightFixture.BRL);
        assertThat(InsightScoring.passesFloor(money(InsightDetector.CATEGORY_SPIKE, "2026-09", "X", amount,
                Confidence.HIGH), r)).isEqualTo(passes);
    }

    @Test
    void newRecurringMerchantAndHealthPointsSkipTheGlobalFloor() {
        assertThat(InsightScoring.passesFloor(money(InsightDetector.NEW_RECURRING_MERCHANT, "2026-06", "m", "12.00",
                Confidence.HIGH), Money.of("4000.00", InsightFixture.BRL))).isTrue();
        InsightCandidate score = new InsightCandidate(InsightDetector.HEALTH_SCORE_CHANGE, "2026-09", "SCORE", null,
                5, false, Confidence.MEDIUM, true, content("score"));
        assertThat(InsightScoring.passesFloor(score, null)).isTrue();
    }

    @ParameterizedTest(name = "A {0} ÷ R 1000 → I {1}")
    @CsvSource({"10, 10", "20, 20", "35, 35", "50, 50", "75, 65", "100, 80", "150, 90", "200, 100", "900, 100"})
    void impactCurveOnTheShareOfTheReference(String amount, int impact) {
        assertThat(InsightScoring.impact(money(InsightDetector.CATEGORY_SPIKE, "2026-09", "X", amount,
                Confidence.HIGH), Money.of("1000.00", InsightFixture.BRL))).isEqualTo(impact);
    }

    @ParameterizedTest(name = "{0} pontos → I {1}")
    @CsvSource({"5, 40", "6, 46", "10, 70", "15, 85", "29, 100"})
    void impactCurveOnHealthPoints(int points, int impact) {
        InsightCandidate candidate = new InsightCandidate(InsightDetector.HEALTH_SCORE_CHANGE, "2026-09", "SCORE",
                null, points, true, Confidence.MEDIUM, true, content("score"));
        assertThat(InsightScoring.impact(candidate, null)).isEqualTo(impact);
    }

    @Test
    void withoutReferenceImpactIsTwenty() {
        assertThat(InsightScoring.impact(money(InsightDetector.UNCATEGORIZED_SHARE, "2026-09", "U", "300.00",
                Confidence.MEDIUM), null)).isEqualTo(20);
    }

    @Test
    void relevanceStartsAtSixtyAndRemembersHistory() {
        InsightCandidate candidate = money(InsightDetector.CATEGORY_SPIKE, "2026-09", "DINING_OUT", "300.00",
                Confidence.HIGH);
        String fingerprint = InsightFingerprint.of(candidate.detector(), WORKSPACE, "2026-09", "DINING_OUT");
        assertThat(InsightScoring.relevance(candidate, fingerprint, List.of(), NOW)).isEqualTo(60);

        Insight lastMonth = stored(InsightDetector.CATEGORY_SPIKE, "2026-08", "DINING_OUT", InsightStatus.EXPIRED);
        assertThat(InsightScoring.relevance(candidate, fingerprint, List.of(lastMonth), NOW)).isEqualTo(75);

        Insight dismissed = stored(InsightDetector.CATEGORY_SPIKE, "2026-08", "DINING_OUT", InsightStatus.DISMISSED);
        // Persistente (+15) e dispensado há 10 dias (−30).
        assertThat(InsightScoring.relevance(candidate, fingerprint, List.of(dismissed), NOW)).isEqualTo(45);

        Insight otherCategory = stored(InsightDetector.CATEGORY_SPIKE, "2026-07", "GROCERIES", InsightStatus.EXPIRED);
        Insight notUseful = new Insight(otherCategory.id(), WORKSPACE, otherCategory.fingerprint(),
                otherCategory.algorithmVersion(), otherCategory.detector(), "2026-07", "GROCERIES",
                InsightStatus.EXPIRED, null, otherCategory.importance(), 50, 60, Confidence.HIGH, 54,
                otherCategory.impactAmount(), null, true, otherCategory.content(), null, null,
                InsightFeedback.NOT_USEFUL, NOW.minus(Duration.ofDays(89)), 1, otherCategory.createdAt(),
                otherCategory.updatedAt(), otherCategory.statusChangedAt());
        assertThat(InsightScoring.relevance(candidate, fingerprint, List.of(notUseful), NOW)).isEqualTo(45);
        // Fora da memória de 90 dias.
        Insight old = new Insight(notUseful.id(), WORKSPACE, notUseful.fingerprint(), notUseful.algorithmVersion(),
                notUseful.detector(), "2026-07", "GROCERIES", InsightStatus.EXPIRED, null, notUseful.importance(), 50,
                60, Confidence.HIGH, 54, notUseful.impactAmount(), null, true, notUseful.content(), null, null,
                InsightFeedback.NOT_USEFUL, NOW.minus(Duration.ofDays(91)), 1, notUseful.createdAt(),
                notUseful.updatedAt(), notUseful.statusChangedAt());
        assertThat(InsightScoring.relevance(candidate, fingerprint, List.of(old), NOW)).isEqualTo(60);
    }

    @Test
    void importantNeedsImpactConfidenceRelevanceAndAnEligibleDetector() {
        Money reference = Money.of("1000.00", InsightFixture.BRL);
        ScoredCandidate important = InsightScoring.score(money(InsightDetector.CATEGORY_SPIKE, "2026-09", "X",
                "50.00", Confidence.MEDIUM), "f", reference, List.of(), NOW);
        assertThat(important.impact()).isEqualTo(50);
        assertThat(important.importance()).isEqualTo(Importance.IMPORTANT);
        // 0,6 · 50 + 0,4 · 60 = 54.
        assertThat(important.priority()).isEqualTo(54);

        assertThat(InsightScoring.score(money(InsightDetector.CATEGORY_SPIKE, "2026-09", "X", "49.00",
                Confidence.MEDIUM), "f", reference, List.of(), NOW).importance()).isEqualTo(Importance.INFORMATIVE);
        assertThat(InsightScoring.score(money(InsightDetector.CATEGORY_SPIKE, "2026-09", "X", "500.00",
                Confidence.LOW), "f", reference, List.of(), NOW).importance()).isEqualTo(Importance.INFORMATIVE);
        assertThat(InsightScoring.score(money(InsightDetector.UNCATEGORIZED_SHARE, "2026-09", "U", "500.00",
                Confidence.MEDIUM), "f", reference, List.of(), NOW).importance()).isEqualTo(Importance.INFORMATIVE);
        InsightCandidate reserveAboveOneMonth = new InsightCandidate(InsightDetector.LOW_RESERVE, null, "2-3",
                Money.of("15000.00", InsightFixture.BRL), null, false, Confidence.HIGH, true, content("r"));
        assertThat(InsightScoring.score(reserveAboveOneMonth, "f", reference, List.of(), NOW).importance())
                .isEqualTo(Importance.INFORMATIVE);
        InsightCandidate scoreDropTen = new InsightCandidate(InsightDetector.HEALTH_SCORE_CHANGE, "2026-09", "SCORE",
                null, 10, true, Confidence.MEDIUM, true, content("s"));
        assertThat(InsightScoring.score(scoreDropTen, "f", reference, List.of(), NOW).importance())
                .isEqualTo(Importance.IMPORTANT);
    }

    @Test
    void dismissingSomethingSimilarKeepsItInformative() {
        Money reference = Money.of("1000.00", InsightFixture.BRL);
        Insight dismissed = stored(InsightDetector.CATEGORY_SPIKE, "2026-07", "X", InsightStatus.DISMISSED);
        ScoredCandidate scored = InsightScoring.score(money(InsightDetector.CATEGORY_SPIKE, "2026-09", "X", "500.00",
                Confidence.HIGH), "f", reference, List.of(dismissed), NOW);
        assertThat(scored.relevance()).isEqualTo(30);
        assertThat(scored.importance()).isEqualTo(Importance.INFORMATIVE);
    }
}
