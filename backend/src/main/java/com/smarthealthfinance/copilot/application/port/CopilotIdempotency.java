package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.UUID;

/**
 * Idempotência do envio de perguntas (ADR-0040 §5), por Workspace e pessoa, com validade curta. Guarda só o hash do
 * corpo, o estado e o {@code requestId}: nunca pergunta, resposta ou valor. A implementação nunca lança: com o
 * armazenamento fora, devolve {@link Reservation.Unavailable} e o Copilot falha fechado (503).
 */
public interface CopilotIdempotency {

    /** Reserva a chave de forma atômica ({@code SET NX}), ou diz por que não foi possível. */
    Reservation reserve(WorkspaceId workspaceId, UserId userId, String key, String bodyHash, UUID requestId);

    /** Marca a chave como respondida; vale pelo restante da janela. */
    void complete(WorkspaceId workspaceId, UserId userId, String key, String bodyHash, UUID requestId);

    /** Libera a chave (resultados 403, 429, 503 e exceções), para a pessoa tentar de novo com a mesma chave. */
    void release(WorkspaceId workspaceId, UserId userId, String key);

    sealed interface Reservation {

        /** A chave é desta requisição. */
        record Reserved() implements Reservation {
        }

        /** Replay de uma chave ainda em processamento, com o mesmo corpo. */
        record InProgress(UUID requestId) implements Reservation {
        }

        /** Replay de uma chave já respondida, com o mesmo corpo. */
        record Done(UUID requestId) implements Reservation {
        }

        /** A mesma chave com outro corpo. */
        record Mismatch() implements Reservation {
        }

        /** Redis fora. */
        record Unavailable() implements Reservation {
        }
    }

}
