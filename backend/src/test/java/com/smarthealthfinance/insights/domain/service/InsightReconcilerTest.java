package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.StatusReason;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.smarthealthfinance.insights.domain.service.InsightScoringTest.NOW;
import static com.smarthealthfinance.insights.domain.service.InsightScoringTest.WORKSPACE;
import static com.smarthealthfinance.insights.domain.service.InsightScoringTest.content;
import static org.assertj.core.api.Assertions.assertThat;

/** Ciclo de vida e deduplicação (ADR-0021 §5, ADR-0022 §4). */
class InsightReconcilerTest {

    private static final String V1 = InsightRules.ALGORITHM_VERSION;
    private static final Instant LATER = NOW.plus(Duration.ofHours(1));
    private static final Money REFERENCE = Money.of("1000.00", InsightFixture.BRL);

    private final Supplier<UUID> ids = UUID::randomUUID;

    private static ScoredCandidate scored(InsightDetector detector, String period, String subject, String amount,
                                          String title) {
        InsightCandidate candidate = new InsightCandidate(detector, period, subject,
                Money.of(amount, InsightFixture.BRL), null, true, Confidence.HIGH, true, content(title));
        return InsightScoring.score(candidate, InsightFingerprint.of(detector, WORKSPACE, period, subject), REFERENCE,
                List.of(), NOW);
    }

    private static ScoredCandidate spike(String amount) {
        return scored(InsightDetector.CATEGORY_SPIKE, "2026-09", "DINING_OUT", amount, "alta " + amount);
    }

    private List<Insight> reconcile(List<Insight> existing, Instant now, ScoredCandidate... candidates) {
        return InsightReconciler.reconcile(WORKSPACE, V1, "2026-09", existing, List.of(candidates), now, ids);
    }

    /** Aplica as mudanças sobre o estado, como a gravação. */
    private static List<Insight> apply(List<Insight> state, List<Insight> changes) {
        List<Insight> next = new ArrayList<>(state);
        for (Insight change : changes) {
            next.removeIf(i -> i.id().equals(change.id()));
            next.add(change);
        }
        return next;
    }

    @Test
    void newCandidateBecomesActiveAndRegeneratingChangesNothing() {
        List<Insight> created = reconcile(List.of(), NOW, spike("40.00"));
        assertThat(created).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(InsightStatus.ACTIVE);
            assertThat(i.revision()).isEqualTo(1);
            assertThat(i.algorithmVersion()).isEqualTo("insights-v1");
            assertThat(i.seenAt()).isNull();
        });
        assertThat(reconcile(created, LATER, spike("40.00"))).isEmpty();
    }

    @Test
    void newNumbersUpdateTheSameInsight() {
        Insight first = reconcile(List.of(), NOW, spike("40.00")).getFirst().withSeenAt(NOW);
        List<Insight> updated = reconcile(List.of(first), LATER, spike("45.00"));
        assertThat(updated).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo(first.id());
            assertThat(i.revision()).isEqualTo(2);
            assertThat(i.updatedAt()).isEqualTo(LATER);
            assertThat(i.content().title()).isEqualTo("alta 45.00");
            // Continua Informativo: o "visto" fica.
            assertThat(i.importance()).isEqualTo(Importance.INFORMATIVE);
            assertThat(i.seenAt()).isEqualTo(NOW);
        });
    }

    @Test
    void becomingImportantMakesItNewAgain() {
        Insight first = reconcile(List.of(), NOW, spike("40.00")).getFirst().withSeenAt(NOW);
        Insight updated = reconcile(List.of(first), LATER, spike("400.00")).getFirst();
        assertThat(updated.importance()).isEqualTo(Importance.IMPORTANT);
        assertThat(updated.seenAt()).isNull();
    }

    @Test
    void conditionGoneInTheSamePeriodResolvesAndCanComeBack() {
        List<Insight> state = reconcile(List.of(), NOW, spike("400.00"));
        List<Insight> resolved = reconcile(state, LATER);
        assertThat(resolved).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(InsightStatus.RESOLVED);
            assertThat(i.statusReason()).isNull();
        });
        Insight back = reconcile(apply(state, resolved), LATER.plusSeconds(60), spike("400.00")).getFirst();
        assertThat(back.status()).isEqualTo(InsightStatus.ACTIVE);
        assertThat(back.id()).isEqualTo(state.getFirst().id());
    }

    @Test
    void periodBoundInsightsExpireWhenThePeriodChanges() {
        List<Insight> august = InsightReconciler.reconcile(WORKSPACE, V1, "2026-08", List.of(),
                List.of(scored(InsightDetector.CATEGORY_SPIKE, "2026-08", "DINING_OUT", "400.00", "agosto")), NOW,
                ids);
        List<Insight> changes = reconcile(august, LATER);
        assertThat(changes).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(InsightStatus.EXPIRED);
            assertThat(i.statusReason()).isEqualTo(StatusReason.PERIOD_ENDED);
        });
    }

    @Test
    void reserveWithoutPeriodResolvesWhenTheBandChanges() {
        List<Insight> state = InsightReconciler.reconcile(WORKSPACE, V1, "2026-08", List.of(),
                List.of(scored(InsightDetector.LOW_RESERVE, null, "2-3", "9000.00", "reserva")), NOW, ids);
        // Em outro período, a mesma faixa continua o mesmo insight.
        assertThat(reconcile(state, LATER, scored(InsightDetector.LOW_RESERVE, null, "2-3", "9000.00", "reserva")))
                .isEmpty();
        assertThat(reconcile(state, LATER, scored(InsightDetector.LOW_RESERVE, null, "1-2", "12000.00", "reserva")))
                .extracting(Insight::subject, Insight::status)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("1-2", InsightStatus.ACTIVE),
                        org.assertj.core.groups.Tuple.tuple("2-3", InsightStatus.RESOLVED));
    }

    @Test
    void dismissedNeverComesBackInAnyVersion() {
        Insight active = reconcile(List.of(), NOW, spike("400.00")).getFirst();
        Insight dismissed = new Insight(active.id(), WORKSPACE, active.fingerprint(), "insights-v0", active.detector(),
                active.periodKey(), active.subject(), InsightStatus.DISMISSED, null, active.importance(),
                active.impact(), active.relevance(), active.confidence(), active.priority(), active.impactAmount(),
                null, true, active.content(), null, NOW, null, null, 1, NOW, NOW, NOW);
        assertThat(reconcile(List.of(dismissed), LATER, spike("400.00"))).isEmpty();
    }

    @Test
    void activeInsightsOfAnotherVersionAreSuperseded() {
        Insight active = reconcile(List.of(), NOW, spike("400.00")).getFirst();
        Insight old = new Insight(UUID.randomUUID(), WORKSPACE, active.fingerprint(), "insights-v0",
                active.detector(), active.periodKey(), active.subject(), InsightStatus.ACTIVE, null,
                active.importance(), active.impact(), active.relevance(), active.confidence(), active.priority(),
                active.impactAmount(), null, true, active.content(), null, null, null, null, 1, NOW, NOW, NOW);
        List<Insight> changes = reconcile(List.of(old), LATER, spike("400.00"));
        assertThat(changes).extracting(Insight::algorithmVersion, Insight::status, Insight::statusReason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("insights-v1", InsightStatus.ACTIVE, null),
                        org.assertj.core.groups.Tuple.tuple("insights-v0", InsightStatus.EXPIRED,
                                StatusReason.VERSION_SUPERSEDED));
    }

    @Test
    void expiredAndDismissedAreNeverRewritten() {
        Insight active = reconcile(List.of(), NOW, spike("400.00")).getFirst();
        Insight expired = active.withStatus(InsightStatus.EXPIRED, StatusReason.PERIOD_ENDED, NOW);
        assertThat(reconcile(List.of(expired), LATER, spike("500.00"))).isEmpty();
    }
}
