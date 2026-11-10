package com.smarthealthfinance.copilot.infrastructure.ratelimit;

import com.smarthealthfinance.copilot.application.port.RateLimiter;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Token bucket por pessoa no Workspace (ADR-0039 §10): script Lua atômico sobre o {@code StringRedisTemplate}, sem
 * dependência nova. O Redis guarda só o balde, com TTL; nunca é fonte da verdade. Redis fora → {@code UNAVAILABLE}
 * (o Copilot falha fechado; o resto do app não usa este componente).
 */
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    static final String KEY_PREFIX = "shf:copilot:rl:";
    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("copilot/token-bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    @Override
    public Decision tryAcquire(WorkspaceId workspaceId, UserId userId, int capacity, Duration refillInterval) {
        Decision decision;
        try {
            List<?> result = redis.execute(SCRIPT, List.of(key(workspaceId, userId)), Integer.toString(capacity),
                    Long.toString(refillInterval.toMillis()));
            decision = decode(result);
        } catch (RuntimeException e) {
            log.warn("Copilot rate limiter unavailable error={}", e.getClass().getSimpleName());
            decision = Decision.unavailable();
        }
        meters.counter("shf.copilot.rate_limit", "status", decision.status().name()).increment();
        return decision;
    }

    static String key(WorkspaceId workspaceId, UserId userId) {
        return KEY_PREFIX + workspaceId.value() + ":" + userId.value();
    }

    private static Decision decode(List<?> result) {
        if (result == null || result.size() != 2) {
            return Decision.unavailable();
        }
        long allowed = ((Number) result.get(0)).longValue();
        long waitMillis = ((Number) result.get(1)).longValue();
        return allowed == 1 ? Decision.allowed() : Decision.denied(Duration.ofMillis(Math.max(1, waitMillis)));
    }
}
