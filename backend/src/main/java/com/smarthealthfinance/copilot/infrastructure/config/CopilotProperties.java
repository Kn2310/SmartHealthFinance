package com.smarthealthfinance.copilot.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * Configuração do Copilot (ADR-0039). O provedor padrão é o simulado; o real exige {@code ANTHROPIC_API_KEY} vinda de
 * variável de ambiente ou secret. Modelos, preços e limites ficam aqui, nunca no código.
 */
@ConfigurationProperties("shf.copilot")
public record CopilotProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("simulated") String provider,
        @DefaultValue("America/Sao_Paulo") ZoneId zone,
        @DefaultValue Models models,
        @DefaultValue Anthropic anthropic,
        @DefaultValue Simulated simulated,
        @DefaultValue Resilience resilience,
        @DefaultValue Limits limits,
        @DefaultValue Pricing pricing) {

    public record Models(
            @DefaultValue("claude-haiku-5-5") String intent,
            @DefaultValue("claude-sonnet-5-5") String answer,
            @DefaultValue("1024") long intentMaxTokens,
            @DefaultValue("4096") long answerMaxTokens,
            @DefaultValue("low") String effort) {
    }

    /** {@code apiKey} nunca vai para log nem para {@code toString}. */
    public record Anthropic(
            @DefaultValue("") String apiKey,
            @DefaultValue("") String baseUrl,
            @DefaultValue("5s") Duration intentTimeout,
            @DefaultValue("25s") Duration answerTimeout,
            @DefaultValue("false") boolean allowCustomBaseUrl) {

        /** Padrão do SDK; com {@code baseUrl} vazio é este host que recebe a chave e as perguntas. */
        public static final String OFFICIAL_HOST = "api.anthropic.com";

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Anthropic[apiKey=" + (hasApiKey() ? "***" : "<unset>") + ", baseUrl=" + baseUrl
                    + ", intentTimeout=" + intentTimeout + ", answerTimeout=" + answerTimeout + "]";
        }
    }

    public record Simulated(@DefaultValue("ok") String mode) {
    }

    public record Resilience(
            @DefaultValue("2") int retryMaxAttempts,
            @DefaultValue("500ms") Duration retryInitialWait,
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("20") int slidingWindowSize,
            @DefaultValue("5") int minimumNumberOfCalls,
            @DefaultValue("30s") Duration openStateDuration,
            @DefaultValue("1") int halfOpenCalls,
            @DefaultValue("16") int maxConcurrentCalls) {
    }

    /** Provisórios até o M12 (ADR-0039 §10). */
    public record Limits(
            @DefaultValue("10") int dailyQuestions,
            @DefaultValue("100") int monthlyQuestions,
            @DefaultValue("1000000") long monthlyInputTokens,
            @DefaultValue("3") int bucketCapacity,
            @DefaultValue("20s") Duration refillInterval) {
    }

    public record Pricing(@DefaultValue("unpriced") String version, @DefaultValue List<Price> models) {
    }

    /** USD por milhão de tokens; a faixa longa é opcional (Haiku 5.5 acima de 100.000 tokens de prompt). */
    public record Price(String model, BigDecimal input, BigDecimal output, BigDecimal cacheRead,
                        BigDecimal cacheWrite, Long longPromptThreshold, BigDecimal longInput,
                        BigDecimal longOutput, BigDecimal longCacheRead, BigDecimal longCacheWrite) {
    }
}
