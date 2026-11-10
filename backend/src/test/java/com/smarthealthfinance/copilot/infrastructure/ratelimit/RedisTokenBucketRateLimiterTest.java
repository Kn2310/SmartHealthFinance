package com.smarthealthfinance.copilot.infrastructure.ratelimit;

import com.smarthealthfinance.copilot.application.port.RateLimiter;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Redis fora ou lento (timeout de 1 s) → o Copilot falha fechado, sem exceção (ADR-0039 §10). */
class RedisTokenBucketRateLimiterTest {

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureIsUnavailableNotAnException() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        RateLimiter.Decision decision = new RedisTokenBucketRateLimiter(redis, meters).tryAcquire(
                new WorkspaceId(UUID.randomUUID()), new UserId(UUID.randomUUID()), 3, Duration.ofSeconds(20));

        assertThat(decision.status()).isEqualTo(RateLimiter.Status.UNAVAILABLE);
        assertThat(meters.counter("shf.copilot.rate_limit", "status", "UNAVAILABLE").count()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unexpectedScriptResultIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L));

        assertThat(new RedisTokenBucketRateLimiter(redis, new SimpleMeterRegistry()).tryAcquire(
                new WorkspaceId(UUID.randomUUID()), new UserId(UUID.randomUUID()), 3, Duration.ofSeconds(20))
                .status()).isEqualTo(RateLimiter.Status.UNAVAILABLE);
    }
}
