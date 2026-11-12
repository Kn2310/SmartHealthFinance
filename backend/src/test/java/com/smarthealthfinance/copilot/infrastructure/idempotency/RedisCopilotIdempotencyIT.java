package com.smarthealthfinance.copilot.infrastructure.idempotency;

import com.smarthealthfinance.copilot.application.port.CopilotIdempotency.Reservation;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Idempotência do Copilot (ADR-0040 §5) contra Redis real (Testcontainers): reserva atômica, estados e chave sem conteúdo. */
class RedisCopilotIdempotencyIT extends IntegrationTest {

    @Autowired
    RedisCopilotIdempotency idempotency;

    @Autowired
    StringRedisTemplate redis;

    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final UserId user = new UserId(UUID.randomUUID());

    @Test
    void reservesOnceThenReplaysInProgressDoneAndMismatch() {
        UUID first = UUID.randomUUID();

        assertThat(idempotency.reserve(workspace, user, "k1", "hash-a", first)).isInstanceOf(Reservation.Reserved.class);
        assertThat(idempotency.reserve(workspace, user, "k1", "hash-a", UUID.randomUUID()))
                .isEqualTo(new Reservation.InProgress(first));
        assertThat(idempotency.reserve(workspace, user, "k1", "hash-b", UUID.randomUUID()))
                .isInstanceOf(Reservation.Mismatch.class);

        UUID answered = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        idempotency.complete(workspace, user, "k1", "hash-a", answered, message);

        assertThat(idempotency.reserve(workspace, user, "k1", "hash-a", UUID.randomUUID()))
                .isEqualTo(new Reservation.Done(answered, message));
        assertThat(idempotency.reserve(workspace, user, "k1", "hash-b", UUID.randomUUID()))
                .as("outro corpo vale mesmo depois de concluído").isInstanceOf(Reservation.Mismatch.class);
    }

    @Test
    void releaseFreesTheKeyAndKeysAreScopedByWorkspaceAndPerson() {
        idempotency.reserve(workspace, user, "k2", "hash", UUID.randomUUID());

        assertThat(idempotency.reserve(new WorkspaceId(UUID.randomUUID()), user, "k2", "hash", UUID.randomUUID()))
                .isInstanceOf(Reservation.Reserved.class);
        assertThat(idempotency.reserve(workspace, new UserId(UUID.randomUUID()), "k2", "hash", UUID.randomUUID()))
                .isInstanceOf(Reservation.Reserved.class);

        idempotency.release(workspace, user, "k2");
        assertThat(idempotency.reserve(workspace, user, "k2", "other-hash", UUID.randomUUID()))
                .isInstanceOf(Reservation.Reserved.class);
    }

    @Test
    void theKeyHasTheDocumentedShapeTtlAndHoldsOnlyStateHashAndRequestId() {
        UUID requestId = UUID.randomUUID();
        idempotency.reserve(workspace, user, "web:minha-chave", "hash-do-corpo", requestId);

        String redisKey = RedisCopilotIdempotency.redisKey(workspace, user, "web:minha-chave");

        assertThat(redisKey).startsWith("shf:copilot:idem:" + workspace.value() + ":" + user.value() + ":")
                .doesNotContain("minha-chave");
        assertThat(redisKey.substring(redisKey.lastIndexOf(':') + 1)).matches("[0-9a-f]{64}");
        assertThat(redis.opsForValue().get(redisKey)).isEqualTo("IN_PROGRESS|hash-do-corpo|" + requestId + "|-");
        assertThat(redis.getExpire(redisKey)).isBetween(590L, 600L);

        idempotency.complete(workspace, user, "web:minha-chave", "hash-do-corpo", requestId, null);
        assertThat(redis.opsForValue().get(redisKey)).isEqualTo("DONE|hash-do-corpo|" + requestId + "|-");
        assertThat(redis.getExpire(redisKey)).isBetween(590L, 600L);
    }

    @Test
    void concurrentReservationsOfTheSameKeyHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Reservation>> attempts = IntStream.range(0, 16)
                    .<Callable<Reservation>>mapToObj(i -> () -> idempotency.reserve(workspace, user, "race", "hash",
                            UUID.randomUUID()))
                    .toList();
            long winners = 0;
            for (Future<Reservation> future : pool.invokeAll(attempts)) {
                if (future.get() instanceof Reservation.Reserved) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
