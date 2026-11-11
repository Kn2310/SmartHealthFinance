package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.copilot.domain.enums.FeedbackRating;
import com.smarthealthfinance.copilot.domain.enums.FeedbackReason;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Feedback por pergunta (ADR-0040 §8): sem conteúdo nem texto livre; vale com ou sem histórico. */
public interface FeedbackStore {

    /** Um feedback; {@code intent} é nulo quando o ledger e o histórico não sabem a intenção. */
    record Feedback(UUID requestId, WorkspaceId workspaceId, UserId userId, FeedbackRating rating,
                    FeedbackReason reason, Intent intent, Map<String, String> versions, Instant now,
                    Instant expiresAt) {

        public Feedback {
            versions = Map.copyOf(versions);
        }
    }

    /** Insere ou atualiza: o último envio vale. A data de expiração original é mantida. */
    void upsert(Feedback feedback);

    /** Retenção: apaga até {@code batch} feedbacks expirados (lock sem espera). */
    int purgeExpired(Instant now, int batch);
}
