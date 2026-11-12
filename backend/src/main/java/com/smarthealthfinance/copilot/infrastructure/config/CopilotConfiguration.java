package com.smarthealthfinance.copilot.infrastructure.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.OutputConfig;
import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.model.CopilotLimits;
import com.smarthealthfinance.copilot.domain.model.ModelPrice;
import com.smarthealthfinance.copilot.domain.model.PipelineSettings;
import com.smarthealthfinance.copilot.domain.model.PricingTable;
import com.smarthealthfinance.copilot.infrastructure.ai.AiProvider;
import com.smarthealthfinance.copilot.infrastructure.ai.AiProviderException;
import com.smarthealthfinance.copilot.infrastructure.ai.AnthropicAiProvider;
import com.smarthealthfinance.copilot.infrastructure.ai.ResilientAiGateway;
import com.smarthealthfinance.copilot.infrastructure.ai.SimulatedAiProvider;
import com.smarthealthfinance.copilot.infrastructure.ai.SimulatedResponder;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

/**
 * Monta o AI Gateway (ADR-0039 §1, §2, §10): escolhe o provedor (simulado por padrão), aplica o Resilience4j e expõe a
 * tabela de preços e os limites ao núcleo de aplicação. Provedor real sem chave falha na inicialização, sem mostrar
 * nenhum valor.
 */
@Configuration
class CopilotConfiguration {

    static final String RESILIENCE_NAME = "copilot-ai";
    private static final Logger log = LoggerFactory.getLogger(CopilotConfiguration.class);

    @Bean
    PricingTable copilotPricingTable(CopilotProperties properties) {
        return PricingTable.of(properties.pricing().version(),
                properties.pricing().models().stream().map(CopilotConfiguration::price).toList());
    }

    @Bean
    CopilotLimits copilotLimits(CopilotProperties properties) {
        CopilotProperties.Limits limits = properties.limits();
        return new CopilotLimits(properties.enabled(), properties.zone(), limits.dailyQuestions(),
                limits.monthlyQuestions(), limits.monthlyInputTokens(), limits.bucketCapacity(),
                limits.refillInterval());
    }

    @Bean
    PipelineSettings copilotPipelineSettings() {
        return PipelineSettings.DEFAULT;
    }

    @Bean
    AiProvider copilotAiProvider(CopilotProperties properties, JsonMapper json) {
        String provider = properties.provider().strip().toLowerCase(Locale.ROOT);
        AiProvider selected = switch (provider) {
            case SimulatedAiProvider.NAME -> new SimulatedAiProvider(
                    SimulatedAiProvider.Mode.valueOf(properties.simulated().mode().strip().toUpperCase(Locale.ROOT)),
                    new SimulatedResponder(json)::respond);
            case AnthropicAiProvider.NAME -> anthropic(properties, json);
            default -> throw new IllegalStateException("shf.copilot.provider must be simulated or anthropic");
        };
        log.info("Copilot AI provider provider={} intentModel={} answerModel={} enabled={}", selected.name(),
                selected.model(AiRole.INTENT),
                selected.model(AiRole.ANSWER), properties.enabled());
        return selected;
    }

    @Bean
    AiGateway copilotAiGateway(AiProvider provider, CopilotProperties properties, MeterRegistry meters) {
        CopilotProperties.Resilience resilience = properties.resilience();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(resilience.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(resilience.slidingWindowSize())
                .minimumNumberOfCalls(resilience.minimumNumberOfCalls())
                .waitDurationInOpenState(resilience.openStateDuration())
                .permittedNumberOfCallsInHalfOpenState(resilience.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(resilience.retryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(resilience.retryInitialWait(), 2.0, 0.5))
                .retryOnException(error -> error instanceof AiProviderException failure && failure.retryable())
                .failAfterMaxAttempts(false)
                .build());
        BulkheadRegistry bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(resilience.maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build());
        CircuitBreaker breaker = breakers.circuitBreaker(RESILIENCE_NAME);
        Retry retry = retries.retry(RESILIENCE_NAME);
        Bulkhead bulkhead = bulkheads.bulkhead(RESILIENCE_NAME);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meters);
        return new ResilientAiGateway(provider, breaker, retry, bulkhead, meters, System::nanoTime);
    }

    private static AiProvider anthropic(CopilotProperties properties, JsonMapper json) {
        CopilotProperties.Anthropic config = properties.anthropic();
        if (!config.hasApiKey()) {
            throw new IllegalStateException(
                    "shf.copilot.provider=anthropic requires ANTHROPIC_API_KEY (environment variable or secret)");
        }
        requireOfficialBaseUrl(config);
        CopilotProperties.Models models = properties.models();
        OutputConfig.Effort effort = OutputConfig.Effort.of(models.effort().strip().toLowerCase(Locale.ROOT));
        return new AnthropicAiProvider(
                new AnthropicAiProvider.Route(client(config, config.intentTimeout()), models.intent(),
                        models.intentMaxTokens(), config.intentTimeout()),
                new AnthropicAiProvider.Route(client(config, config.answerTimeout()), models.answer(),
                        models.answerMaxTokens(), config.answerTimeout()),
                effort, json);
    }

    /**
     * A chave e as perguntas só vão para {@code https://api.anthropic.com} (ADR-0039 §7). Outro host só com
     * {@code shf.copilot.anthropic.allow-custom-base-url=true}, previsto para testes. A mensagem nunca traz a chave.
     *
     * @throws IllegalStateException {@code baseUrl} que não é https no host oficial, sem a permissão explícita
     */
    static void requireOfficialBaseUrl(CopilotProperties.Anthropic config) {
        String baseUrl = config.baseUrl();
        if (baseUrl == null || baseUrl.isBlank() || config.allowCustomBaseUrl()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(baseUrl.strip());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("shf.copilot.anthropic.base-url is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !CopilotProperties.Anthropic.OFFICIAL_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null) {
            throw new IllegalStateException("shf.copilot.anthropic.base-url must be https://"
                    + CopilotProperties.Anthropic.OFFICIAL_HOST + " (set shf.copilot.anthropic.allow-custom-base-url"
                    + "=true only in tests)");
        }
    }

    /** Sem retries do SDK: o retry é do Resilience4j, dentro do orçamento do pipeline. */
    private static AnthropicClient client(CopilotProperties.Anthropic config, Duration timeout) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .maxRetries(0)
                .timeout(timeout);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    private static ModelPrice price(CopilotProperties.Price price) {
        ModelPrice.Rates rates = new ModelPrice.Rates(price.input(), price.output(), price.cacheRead(),
                price.cacheWrite());
        if (price.longPromptThreshold() == null) {
            return new ModelPrice(price.model(), rates, 0, null);
        }
        return new ModelPrice(price.model(), rates, price.longPromptThreshold(), new ModelPrice.Rates(
                price.longInput(), price.longOutput(), price.longCacheRead(), price.longCacheWrite()));
    }
}
