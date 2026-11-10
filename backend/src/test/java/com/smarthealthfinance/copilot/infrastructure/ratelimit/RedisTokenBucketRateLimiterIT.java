package com.smarthealthfinance.copilot.infrastructure.ratelimit;

import com.smarthealthfinance.copilot.application.port.RateLimiter;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Token bucket do Copilot (ADR-0039 §10) contra Redis real (Testcontainers). Cada teste usa ids próprios. */
class RedisTokenBucketRateLimiterIT extends IntegrationTest {

    private static final Duration REFILL = Duration.ofSeconds(20);

    @Autowired
    RedisTokenBucketRateLimiter limiter;

    @Autowired
    StringRedisTemplate redis;

    private static WorkspaceId workspace() {
        return new WorkspaceId(UUID.randomUUID());
    }

    private static UserId user() {
        return new UserId(UUID.randomUUID());
    }

    @Test
    void allowsTheCapacityThenDeniesWithRetryAfter() {
        WorkspaceId workspace = workspace();
        UserId user = user();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(workspace, user, 3, REFILL).status()).isEqualTo(RateLimiter.Status.ALLOWED);
        }
        RateLimiter.Decision denied = limiter.tryAcquire(workspace, user, 3, REFILL);

        assertThat(denied.status()).isEqualTo(RateLimiter.Status.DENIED);
        assertThat(denied.retryAfter()).isPositive().isLessThanOrEqualTo(REFILL);
    }

    @Test
    void refillsOneQuestionPerInterval() throws InterruptedException {
        WorkspaceId workspace = workspace();
        UserId user = user();
        Duration refill = Duration.ofMillis(300);

        assertThat(limiter.tryAcquire(workspace, user, 1, refill).status()).isEqualTo(RateLimiter.Status.ALLOWED);
        assertThat(limiter.tryAcquire(workspace, user, 1, refill).status()).isEqualTo(RateLimiter.Status.DENIED);
        Thread.sleep(450);

        assertThat(limiter.tryAcquire(workspace, user, 1, refill).status()).isEqualTo(RateLimiter.Status.ALLOWED);
    }

    @Test
    void bucketsArePerPersonAndPerWorkspace() {
        WorkspaceId workspace = workspace();
        UserId ana = user();
        assertThat(limiter.tryAcquire(workspace, ana, 1, REFILL).status()).isEqualTo(RateLimiter.Status.ALLOWED);
        assertThat(limiter.tryAcquire(workspace, ana, 1, REFILL).status()).isEqualTo(RateLimiter.Status.DENIED);

        assertThat(limiter.tryAcquire(workspace, user(), 1, REFILL).status()).isEqualTo(RateLimiter.Status.ALLOWED);
        assertThat(limiter.tryAcquire(workspace(), ana, 1, REFILL).status()).isEqualTo(RateLimiter.Status.ALLOWED);
    }

    @Test
    void keyHasATtlAndCarriesOnlyIds() {
        WorkspaceId workspace = workspace();
        UserId user = user();
        limiter.tryAcquire(workspace, user, 3, REFILL);

        String key = RedisTokenBucketRateLimiter.key(workspace, user);
        assertThat(key).isEqualTo("shf:copilot:rl:" + workspace.value() + ":" + user.value());
        Long ttl = redis.getExpire(key);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(3 * 20 * 2);
        assertThat(redis.<String, String>opsForHash().keys(key)).containsExactlyInAnyOrder("tokens", "ts");
    }
}
