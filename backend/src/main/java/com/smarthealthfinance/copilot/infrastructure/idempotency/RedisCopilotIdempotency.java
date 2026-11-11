package com.smarthealthfinance.copilot.infrastructure.idempotency;

import com.smarthealthfinance.copilot.application.port.CopilotIdempotency;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Idempotência do envio de perguntas no Redis (ADR-0040 §5), sobre o {@code StringRedisTemplate}, sem dependência
 * nova. Chave {@code shf:copilot:idem:{workspaceId}:{userId}:{sha256(key)}} por 10 minutos; o valor é só
 * {@code estado|hashDoCorpo|requestId|messageId}: nunca pergunta, resposta ou valor. A reserva é atômica ({@code SET NX}). Redis
 * fora: {@link Reservation.Unavailable} (falha fechada, só para o Copilot); nunca lança.
 */
@Component
public class RedisCopilotIdempotency implements CopilotIdempotency {

    static final String KEY_PREFIX = "shf:copilot:idem:";
    static final Duration TTL = Duration.ofMinutes(10);
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String DONE = "DONE";
    private static final String NO_MESSAGE = "-";
    private static final Logger log = LoggerFactory.getLogger(RedisCopilotIdempotency.class);

    private final StringRedisTemplate redis;

    public RedisCopilotIdempotency(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Reservation reserve(WorkspaceId workspaceId, UserId userId, String key, String bodyHash, UUID requestId) {
        String redisKey = redisKey(workspaceId, userId, key);
        try {
            // Duas voltas bastam: a chave pode expirar entre o SET NX que falhou e a leitura.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey,
                        value(IN_PROGRESS, bodyHash, requestId, null), TTL))) {
                    return new Reservation.Reserved();
                }
                String stored = redis.opsForValue().get(redisKey);
                if (stored != null) {
                    return replay(stored, bodyHash);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Copilot idempotency unavailable error={}", e.getClass().getSimpleName());
        }
        return new Reservation.Unavailable();
    }

    @Override
    public void complete(WorkspaceId workspaceId, UserId userId, String key, String bodyHash, UUID requestId,
                         UUID messageId) {
        try {
            redis.opsForValue().set(redisKey(workspaceId, userId, key), value(DONE, bodyHash, requestId, messageId), TTL);
        } catch (RuntimeException e) {
            log.warn("Copilot idempotency complete failed error={}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void release(WorkspaceId workspaceId, UserId userId, String key) {
        try {
            redis.delete(redisKey(workspaceId, userId, key));
        } catch (RuntimeException e) {
            log.warn("Copilot idempotency release failed error={}", e.getClass().getSimpleName());
        }
    }

    static String redisKey(WorkspaceId workspaceId, UserId userId, String key) {
        return KEY_PREFIX + workspaceId.value() + ":" + userId.value() + ":" + sha256(key);
    }

    private static String value(String state, String bodyHash, UUID requestId, UUID messageId) {
        return state + "|" + bodyHash + "|" + requestId + "|" + (messageId == null ? NO_MESSAGE : messageId);
    }

    private static Reservation replay(String stored, String bodyHash) {
        String[] parts = stored.split("\\|", 4);
        if (parts.length != 4) {
            return new Reservation.Unavailable();
        }
        if (!parts[1].equals(bodyHash)) {
            return new Reservation.Mismatch();
        }
        UUID requestId = UUID.fromString(parts[2]);
        UUID messageId = NO_MESSAGE.equals(parts[3]) ? null : UUID.fromString(parts[3]);
        return DONE.equals(parts[0]) ? new Reservation.Done(requestId, messageId)
                : new Reservation.InProgress(requestId);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
