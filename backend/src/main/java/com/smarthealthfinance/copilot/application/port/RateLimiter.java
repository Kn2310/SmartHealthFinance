package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Duration;
import java.util.Objects;

/**
 * Rate limit por pessoa no Workspace (ADR-0039 §10): token bucket. A implementação nunca lança: com o armazenamento
 * fora, devolve {@code UNAVAILABLE} e o Copilot falha fechado.
 */
public interface RateLimiter {

    Decision tryAcquire(WorkspaceId workspaceId, UserId userId, int capacity, Duration refillInterval);

    enum Status { ALLOWED, DENIED, UNAVAILABLE }

    /** {@code retryAfter}: quando a próxima pergunta é aceita (zero se permitida ou indisponível). */
    record Decision(Status status, Duration retryAfter) {
        public Decision {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(retryAfter, "retryAfter");
        }

        public static Decision allowed() {
            return new Decision(Status.ALLOWED, Duration.ZERO);
        }

        public static Decision denied(Duration retryAfter) {
            return new Decision(Status.DENIED, retryAfter);
        }

        public static Decision unavailable() {
            return new Decision(Status.UNAVAILABLE, Duration.ZERO);
        }
    }
}
