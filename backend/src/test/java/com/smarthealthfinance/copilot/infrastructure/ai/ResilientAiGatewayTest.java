package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.application.port.AiResult;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Resiliência do AI Gateway (ADR-0039 §2): nenhuma falha vira exceção, e só falha repetível é repetida. */
@ExtendWith(OutputCaptureExtension.class)
class ResilientAiGatewayTest {

    private static final AiRequest REQUEST =
            new AiRequest("prompt secreto de sistema", "pergunta com R$ 1.234,56", "{\"type\":\"object\"}");
    private static final TokenUsage USAGE = new TokenUsage(100, 20, 0, 0);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong clock = new AtomicLong();

    /** Provedor com respostas roteirizadas; conta as chamadas. */
    private static final class ScriptedProvider implements AiProvider {
        final Deque<Supplier<Reply>> script = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        Supplier<Reply> fallback = () -> new Reply(AiOutcome.OK, "{\"ok\":true}", "model-a", USAGE);

        ScriptedProvider then(Supplier<Reply> step) {
            script.add(step);
            return this;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public String model(AiRole role) {
            return role == AiRole.INTENT ? "model-intent" : "model-answer";
        }

        @Override
        public Reply call(AiRole role, AiRequest request) {
            calls.incrementAndGet();
            Supplier<Reply> step = script.poll();
            return (step == null ? fallback : step).get();
        }
    }

    private static Supplier<AiProvider.Reply> failing(AiOutcome outcome, boolean retryable) {
        return () -> {
            throw new AiProviderException(outcome, retryable, "test");
        };
    }

    private ResilientAiGateway gateway(AiProvider provider, CircuitBreakerConfig breaker, int maxConcurrent) {
        Retry retry = Retry.of("t", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryOnException(e -> e instanceof AiProviderException failure && failure.retryable())
                .build());
        Bulkhead bulkhead = Bulkhead.of("t", BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent).maxWaitDuration(Duration.ZERO).build());
        lastBreaker = CircuitBreaker.of("t", breaker);
        return new ResilientAiGateway(provider, lastBreaker, retry, bulkhead, meters,
                () -> clock.addAndGet(1_000_000));
    }

    private CircuitBreaker lastBreaker;

    private ResilientAiGateway gateway(AiProvider provider) {
        return gateway(provider, CircuitBreakerConfig.ofDefaults(), 16);
    }

    @Test
    void okReplyCarriesJsonUsageModelAndLatency() {
        AiResult result = gateway(new ScriptedProvider()).answer(REQUEST);

        assertThat(result.outcome()).isEqualTo(AiOutcome.OK);
        assertThat(result.output()).contains("{\"ok\":true}");
        assertThat(result.usage()).isEqualTo(USAGE);
        assertThat(result.model()).isEqualTo("model-a");
        assertThat(result.provider()).isEqualTo("scripted");
        assertThat(result.latency()).isPositive();
        assertThat(meters.counter("shf.copilot.ai.requests", "role", "ANSWER", "provider", "scripted", "outcome",
                "OK").count()).isEqualTo(1.0);
        assertThat(meters.find("shf.copilot.ai.latency").tag("role", "ANSWER").timer().count()).isEqualTo(1);
    }

    @Test
    void refusalAndInvalidOutputAreResultsNotFailures() {
        ScriptedProvider provider = new ScriptedProvider()
                .then(() -> new AiProvider.Reply(AiOutcome.REFUSED, null, "model-a", USAGE))
                .then(() -> new AiProvider.Reply(AiOutcome.INVALID_OUTPUT, null, "model-a", USAGE));
        ResilientAiGateway gateway = gateway(provider);

        assertThat(gateway.classify(REQUEST).outcome()).isEqualTo(AiOutcome.REFUSED);
        AiResult invalid = gateway.classify(REQUEST);
        assertThat(invalid.outcome()).isEqualTo(AiOutcome.INVALID_OUTPUT);
        assertThat(invalid.output()).isEmpty();
        assertThat(invalid.usage()).isEqualTo(USAGE);
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void retryableFailureIsRetriedOnce() {
        ScriptedProvider provider = new ScriptedProvider().then(failing(AiOutcome.UNAVAILABLE, true));

        AiResult result = gateway(provider).answer(REQUEST);

        assertThat(result.outcome()).isEqualTo(AiOutcome.OK);
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void rateLimitThatPersistsEndsAsRateLimitedAfterOneRetry() {
        ScriptedProvider provider = new ScriptedProvider();
        provider.fallback = failing(AiOutcome.RATE_LIMITED, true);

        AiResult result = gateway(provider).answer(REQUEST);

        assertThat(result.outcome()).isEqualTo(AiOutcome.RATE_LIMITED);
        assertThat(result.output()).isEmpty();
        assertThat(result.usage()).isEqualTo(TokenUsage.NONE);
        assertThat(result.model()).isEqualTo("model-answer");
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void readTimeoutIsNotRetried() {
        ScriptedProvider provider = new ScriptedProvider();
        provider.fallback = failing(AiOutcome.TIMEOUT, false);

        assertThat(gateway(provider).answer(REQUEST).outcome()).isEqualTo(AiOutcome.TIMEOUT);
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void openCircuitShortCircuitsWithoutCallingTheProvider() {
        ScriptedProvider provider = new ScriptedProvider();
        provider.fallback = failing(AiOutcome.UNAVAILABLE, false);
        ResilientAiGateway gateway = gateway(provider, CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build(), 16);

        for (int i = 0; i < 4; i++) {
            assertThat(gateway.answer(REQUEST).outcome()).isEqualTo(AiOutcome.UNAVAILABLE);
        }
        int callsWhenOpen = provider.calls.get();
        AiResult shortCircuited = gateway.answer(REQUEST);

        assertThat(shortCircuited.outcome()).isEqualTo(AiOutcome.UNAVAILABLE);
        assertThat(provider.calls).hasValue(callsWhenOpen);
        assertThat(meters.counter("shf.copilot.ai.short_circuited", "role", "ANSWER").count()).isEqualTo(1.0);
    }

    @Test
    void fullBulkheadIsUnavailableNotBlocking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedProvider provider = new ScriptedProvider().then(() -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AiProvider.Reply(AiOutcome.OK, "{}", "model-a", USAGE);
        });
        ResilientAiGateway gateway = gateway(provider, CircuitBreakerConfig.ofDefaults(), 1);

        Thread busy = Thread.ofVirtual().start(() -> gateway.answer(REQUEST));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        AiResult rejected = gateway.answer(REQUEST);
        release.countDown();
        busy.join(5_000);

        assertThat(rejected.outcome()).isEqualTo(AiOutcome.UNAVAILABLE);
        assertThat(meters.counter("shf.copilot.ai.bulkhead_full", "role", "ANSWER").count()).isEqualTo(1.0);
        // Concorrência esgotada não é falha do provedor: não pode abrir o circuito.
        assertThat(lastBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void unexpectedErrorIsUnavailableAndLogsNoContent(CapturedOutput output) {
        ScriptedProvider provider = new ScriptedProvider().then(() -> {
            throw new IllegalStateException("pergunta com R$ 1.234,56");
        });

        AiResult result = gateway(provider).answer(REQUEST);

        assertThat(result.outcome()).isEqualTo(AiOutcome.UNAVAILABLE);
        assertThat(output).contains("IllegalStateException")
                .doesNotContain("1.234,56")
                .doesNotContain("prompt secreto");
        assertThat(REQUEST.toString()).doesNotContain("secreto").doesNotContain("1.234,56");
    }
}
