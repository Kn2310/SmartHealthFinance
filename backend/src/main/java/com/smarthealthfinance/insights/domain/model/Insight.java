package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightFeedback;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.InsightType;
import com.smarthealthfinance.insights.domain.exception.InsightNotActiveException;
import com.smarthealthfinance.insights.domain.enums.StatusReason;
import com.smarthealthfinance.shared.domain.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Um insight guardado (ADR-0021 §5, ADR-0022 §5): identidade por (Workspace, fingerprint, versão), ciclo de vida,
 * pontuação e o conteúdo que o usuário leu. {@code seenAt}, {@code dismissedAt} e {@code feedback} são estado do
 * usuário: a geração nunca os apaga.
 *
 * @param revision sobe só quando algo derivado mudou
 */
public record Insight(UUID id, UUID workspaceId, String fingerprint, String algorithmVersion,
                      InsightDetector detector, String periodKey, String subject, InsightStatus status,
                      StatusReason statusReason, Importance importance, int impact, int relevance,
                      Confidence confidence, int priority, Money impactAmount, Integer impactPoints, boolean partial,
                      InsightContent content, Instant seenAt, Instant dismissedAt, InsightFeedback feedback,
                      Instant feedbackAt, int revision, Instant createdAt, Instant updatedAt,
                      Instant statusChangedAt) {

    public Insight {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(importance, "importance");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        if ((status == InsightStatus.DISMISSED) != (dismissedAt != null)) {
            throw new IllegalArgumentException("Só insight dispensado tem dismissedAt");
        }
        if ((feedback == null) != (feedbackAt == null)) {
            throw new IllegalArgumentException("Feedback sem data");
        }
    }

    /** Insight novo, {@code ACTIVE}, a partir de um candidato pontuado. */
    public static Insight create(UUID id, UUID workspaceId, String algorithmVersion, ScoredCandidate scored,
                                 Instant now) {
        InsightCandidate c = scored.candidate();
        return new Insight(id, workspaceId, scored.fingerprint(), algorithmVersion, c.detector(), c.periodKey(),
                c.subject(), InsightStatus.ACTIVE, null, scored.importance(), scored.impact(), scored.relevance(),
                c.confidence(), scored.priority(), c.impactAmount(), c.impactPoints(), c.partial(), c.content(), null,
                null, null, null, 1, now, now, now);
    }

    public InsightType type() {
        return detector.type();
    }

    public boolean visible() {
        return status == InsightStatus.ACTIVE;
    }

    /** Os números, textos e a pontuação vindos de um novo cálculo (sem tocar no estado do usuário nem nas datas). */
    public Insight withDerived(ScoredCandidate scored) {
        InsightCandidate c = scored.candidate();
        return new Insight(id, workspaceId, fingerprint, algorithmVersion, detector, periodKey, subject, status,
                statusReason, scored.importance(), scored.impact(), scored.relevance(), c.confidence(),
                scored.priority(), c.impactAmount(), c.impactPoints(), c.partial(), c.content(), seenAt, dismissedAt,
                feedback, feedbackAt, revision, createdAt, updatedAt, statusChangedAt);
    }

    public Insight withStatus(InsightStatus value, StatusReason reason, Instant now) {
        return new Insight(id, workspaceId, fingerprint, algorithmVersion, detector, periodKey, subject, value, reason,
                importance, impact, relevance, confidence, priority, impactAmount, impactPoints, partial, content,
                seenAt, dismissedAt, feedback, feedbackAt, revision, createdAt, updatedAt, now);
    }

    public Insight withSeenAt(Instant value) {
        return new Insight(id, workspaceId, fingerprint, algorithmVersion, detector, periodKey, subject, status,
                statusReason, importance, impact, relevance, confidence, priority, impactAmount, impactPoints, partial,
                content, value, dismissedAt, feedback, feedbackAt, revision, createdAt, updatedAt, statusChangedAt);
    }

    /** "Visto" (ADR-0023 §3): a primeira vez fica; repetir não muda nada. */
    public Insight markSeen(Instant now) {
        return seenAt != null ? this : withSeenAt(now);
    }

    /**
     * Dispensa (ADR-0021 §8): só um insight {@code ACTIVE}; dispensar de novo não muda nada.
     *
     * @throws InsightNotActiveException se o insight foi resolvido ou expirou
     */
    public Insight dismiss(Instant now) {
        if (status == InsightStatus.DISMISSED) {
            return this;
        }
        if (status != InsightStatus.ACTIVE) {
            throw new InsightNotActiveException();
        }
        return new Insight(id, workspaceId, fingerprint, algorithmVersion, detector, periodKey, subject,
                InsightStatus.DISMISSED, null, importance, impact, relevance, confidence, priority, impactAmount,
                impactPoints, partial, content, seenAt, now, feedback, feedbackAt, revision, createdAt, updatedAt,
                now);
    }

    /** "Útil / Não útil" (ADR-0021 §8): vale o último; {@code null} remove. Repetir o mesmo valor não muda nada. */
    public Insight withFeedback(InsightFeedback value, Instant now) {
        if (value == feedback) {
            return this;
        }
        return new Insight(id, workspaceId, fingerprint, algorithmVersion, detector, periodKey, subject, status,
                statusReason, importance, impact, relevance, confidence, priority, impactAmount, impactPoints, partial,
                content, seenAt, dismissedAt, value, value == null ? null : now, revision, createdAt, updatedAt,
                statusChangedAt);
    }

    /** Marca uma nova revisão do conteúdo derivado. */
    public Insight revised(Instant now) {
        return new Insight(id, workspaceId, fingerprint, algorithmVersion, detector, periodKey, subject, status,
                statusReason, importance, impact, relevance, confidence, priority, impactAmount, impactPoints, partial,
                content, seenAt, dismissedAt, feedback, feedbackAt, revision + 1, createdAt, now, statusChangedAt);
    }

    /** Mesmo conteúdo derivado (números, textos, pontuação), ignorando estado e datas. */
    public boolean sameDerived(Insight other) {
        return importance == other.importance && impact == other.impact && relevance == other.relevance
                && confidence == other.confidence && priority == other.priority
                && Objects.equals(impactAmount, other.impactAmount)
                && Objects.equals(impactPoints, other.impactPoints) && partial == other.partial
                && content.equals(other.content);
    }
}
