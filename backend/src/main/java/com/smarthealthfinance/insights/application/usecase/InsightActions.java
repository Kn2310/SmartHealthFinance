package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.exception.InsightNotFoundException;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.domain.enums.InsightFeedback;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ações do usuário sobre um insight (ADR-0023 §3): visto, dispensar e feedback. Idempotentes pelo estado (repetir não
 * muda nada). Bloqueiam o Workspace como a geração (ADR-0022 §3), para uma geração concorrente nunca sobrescrever o
 * estado do usuário com uma leitura velha. Não disparam nova geração.
 */
@Service
public class InsightActions {

    private static final Logger log = LoggerFactory.getLogger(InsightActions.class);

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final InsightStore store;
    private final Clock clock;

    public InsightActions(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, InsightStore store,
                          Clock clock) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public Insight markSeen(UUID workspaceId, UUID insightId) {
        return apply(workspaceId, insightId, "seen", insight -> insight.markSeen(clock.instant()));
    }

    /** @throws com.smarthealthfinance.insights.domain.exception.InsightNotActiveException resolvido ou expirado */
    @Transactional
    public Insight dismiss(UUID workspaceId, UUID insightId) {
        return apply(workspaceId, insightId, "dismiss", insight -> insight.dismiss(clock.instant()));
    }

    /** @param value {@code USEFUL}, {@code NOT_USEFUL} ou nulo (remove) */
    @Transactional
    public Insight feedback(UUID workspaceId, UUID insightId, String value) {
        WorkspaceId id = authorize(workspaceId);
        InsightFeedback feedback = feedback(value);
        return apply(id, insightId, "feedback", insight -> insight.withFeedback(feedback, clock.instant()));
    }

    private Insight apply(UUID workspaceId, UUID insightId, String action, UnaryOperator<Insight> change) {
        return apply(authorize(workspaceId), insightId, action, change);
    }

    private Insight apply(WorkspaceId id, UUID insightId, String action, UnaryOperator<Insight> change) {
        store.lock(id);
        Insight current = store.findById(id, insightId).orElseThrow(InsightNotFoundException::new);
        Insight updated = change.apply(current);
        if (!updated.equals(current)) {
            store.save(List.of(updated));
            log.info("Insight action applied workspaceId={} insightId={} action={}", id, insightId, action);
        }
        return updated;
    }

    private WorkspaceId authorize(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return id;
    }

    private static InsightFeedback feedback(String value) {
        if (value == null) {
            return null;
        }
        try {
            return InsightFeedback.valueOf(value.strip());
        }
        catch (IllegalArgumentException invalid) {
            throw new InvalidValueException("value", "INVALID");
        }
    }
}
