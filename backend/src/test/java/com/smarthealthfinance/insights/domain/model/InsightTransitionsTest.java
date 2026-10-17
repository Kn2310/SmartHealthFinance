package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightFeedback;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.StatusReason;
import com.smarthealthfinance.insights.domain.exception.InsightNotActiveException;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ações do usuário (ADR-0023 §3): idempotentes pelo estado. */
class InsightTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    private static Insight active() {
        InsightContent content = new InsightContent("título", "apoio", "Assunto", null, null,
                new InsightEvidence(List.of(), List.of(), List.of(), "como", List.of(), List.of()),
                List.of(InsightAction.primary(ActionKind.VIEW_HEALTH, "Ver saúde", Map.of())), InsightRefs.none());
        InsightCandidate candidate = new InsightCandidate(InsightDetector.CATEGORY_SPIKE, "2026-10", "X",
                Money.of("300.00", Currency.getInstance("BRL")), null, true, Confidence.HIGH, true, content);
        return Insight.create(UUID.randomUUID(), UUID.randomUUID(), "insights-v1",
                new ScoredCandidate(candidate, "f", 80, 60, Importance.IMPORTANT, 72), NOW);
    }

    @Test
    void seenKeepsTheFirstTime() {
        Insight seen = active().markSeen(NOW);
        assertThat(seen.seenAt()).isEqualTo(NOW);
        assertThat(seen.markSeen(LATER)).isSameAs(seen);
    }

    @Test
    void dismissOnlyActiveAndRepeatingChangesNothing() {
        Insight dismissed = active().dismiss(NOW);
        assertThat(dismissed.status()).isEqualTo(InsightStatus.DISMISSED);
        assertThat(dismissed.dismissedAt()).isEqualTo(NOW);
        assertThat(dismissed.statusChangedAt()).isEqualTo(NOW);
        assertThat(dismissed.dismiss(LATER)).isSameAs(dismissed);

        Insight resolved = active().withStatus(InsightStatus.RESOLVED, null, NOW);
        assertThatThrownBy(() -> resolved.dismiss(LATER)).isInstanceOf(InsightNotActiveException.class);
        Insight expired = active().withStatus(InsightStatus.EXPIRED, StatusReason.PERIOD_ENDED, NOW);
        assertThatThrownBy(() -> expired.dismiss(LATER)).isInstanceOf(InsightNotActiveException.class);
    }

    @Test
    void feedbackLastWinsAndNullRemoves() {
        Insight useful = active().withFeedback(InsightFeedback.USEFUL, NOW);
        assertThat(useful.feedback()).isEqualTo(InsightFeedback.USEFUL);
        assertThat(useful.feedbackAt()).isEqualTo(NOW);
        assertThat(useful.withFeedback(InsightFeedback.USEFUL, LATER)).isSameAs(useful);

        Insight notUseful = useful.withFeedback(InsightFeedback.NOT_USEFUL, LATER);
        assertThat(notUseful.feedbackAt()).isEqualTo(LATER);
        Insight cleared = notUseful.withFeedback(null, LATER);
        assertThat(cleared.feedback()).isNull();
        assertThat(cleared.feedbackAt()).isNull();
    }
}
