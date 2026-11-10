package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.application.port.AiResult;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Implementação da porta {@link AiGateway} (ADR-0039 §2): retry (só falha repetível) → bulkhead → circuit breaker →
 * provedor. Nunca lança: circuito aberto e concorrência esgotada viram {@code UNAVAILABLE}, e qualquer falha vira um
 * {@code outcome}. O núcleo financeiro não é tocado aqui: sem transação, sem banco, sem estado compartilhado.
 */
public final class ResilientAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientAiGateway.class);

    private final AiProvider provider;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final MeterRegistry meters;
    private final LongSupplier nanoTime;

    public ResilientAiGateway(AiProvider provider, CircuitBreaker circuitBreaker, Retry retry, Bulkhead bulkhead,
                              MeterRegistry meters, LongSupplier nanoTime) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public AiResult classify(AiRequest request) {
        return call(AiRole.INTENT, request);
    }

    @Override
    public AiResult answer(AiRequest request) {
        return call(AiRole.ANSWER, request);
    }

    private AiResult call(AiRole role, AiRequest request) {
        Objects.requireNonNull(request, "request");
        long start = nanoTime.getAsLong();
        // O bulkhead fica fora do circuito: concorrência esgotada não conta como falha do provedor.
        Supplier<AiProvider.Reply> decorated = Retry.decorateSupplier(retry,
                Bulkhead.decorateSupplier(bulkhead,
                        CircuitBreaker.decorateSupplier(circuitBreaker, () -> provider.call(role, request))));
        String model = provider.model(role);
        AiResult result;
        try {
            AiProvider.Reply reply = decorated.get();
            Duration latency = elapsed(start);
            result = reply.outcome() == AiOutcome.OK
                    ? AiResult.ok(reply.json(), provider.name(), reply.model(), reply.usage(), latency)
                    : AiResult.failed(reply.outcome(), provider.name(), reply.model(), reply.usage(), latency);
        } catch (AiProviderException e) {
            result = AiResult.failed(e.outcome(), provider.name(), model, TokenUsage.NONE, elapsed(start));
        } catch (CallNotPermittedException e) {
            result = AiResult.failed(AiOutcome.UNAVAILABLE, provider.name(), model, TokenUsage.NONE, elapsed(start));
            meters.counter("shf.copilot.ai.short_circuited", "role", role.name()).increment();
        } catch (BulkheadFullException e) {
            result = AiResult.failed(AiOutcome.UNAVAILABLE, provider.name(), model, TokenUsage.NONE, elapsed(start));
            meters.counter("shf.copilot.ai.bulkhead_full", "role", role.name()).increment();
        } catch (RuntimeException e) {
            log.error("Copilot gateway unexpected failure provider={} role={} error={}", provider.name(), role,
                    e.getClass().getSimpleName());
            result = AiResult.failed(AiOutcome.UNAVAILABLE, provider.name(), model, TokenUsage.NONE, elapsed(start));
        }

        String outcome = result.outcome().name();
        meters.counter("shf.copilot.ai.requests", "role", role.name(), "provider", provider.name(), "outcome",
                outcome).increment();
        Timer.builder("shf.copilot.ai.latency").tag("role", role.name()).tag("outcome", outcome)
                .register(meters).record(result.latency());
        return result;
    }

    private Duration elapsed(long start) {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - start));
    }
}
