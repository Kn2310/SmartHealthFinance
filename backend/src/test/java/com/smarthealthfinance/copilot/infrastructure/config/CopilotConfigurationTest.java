package com.smarthealthfinance.copilot.infrastructure.config;

import com.smarthealthfinance.copilot.infrastructure.ai.AnthropicAiProvider;
import com.smarthealthfinance.copilot.infrastructure.ai.SimulatedAiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Escolha do provedor (ADR-0039 §1, §2): simulado por padrão; real sem chave não sobe e não mostra valor algum. */
class CopilotConfigurationTest {

    private final CopilotConfiguration configuration = new CopilotConfiguration();
    private final JsonMapper json = JsonMapper.builder().build();

    private static CopilotProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("shf.copilot", CopilotProperties.class);
    }

    @Test
    void defaultsToTheSimulatedProvider() {
        assertThat(configuration.copilotAiProvider(bind(Map.of()), json)).isInstanceOf(SimulatedAiProvider.class);
    }

    @Test
    void realProviderWithoutKeyFailsNamingOnlyTheVariable() {
        Map<String, String> values = new HashMap<>();
        values.put("shf.copilot.provider", "anthropic");
        values.put("shf.copilot.anthropic.api-key", "   ");

        assertThatThrownBy(() -> configuration.copilotAiProvider(bind(values), json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void realProviderWithKeyBuildsTheAnthropicAdapterAndNeverPrintsTheKey() {
        Map<String, String> values = new HashMap<>();
        values.put("shf.copilot.provider", "anthropic");
        values.put("shf.copilot.anthropic.api-key", "sk-test-not-real");
        CopilotProperties properties = bind(values);

        assertThat(configuration.copilotAiProvider(properties, json)).isInstanceOf(AnthropicAiProvider.class);
        assertThat(properties.toString()).doesNotContain("sk-test-not-real").contains("apiKey=***");
    }

    private static Map<String, String> anthropic(String baseUrl) {
        Map<String, String> values = new HashMap<>();
        values.put("shf.copilot.provider", "anthropic");
        values.put("shf.copilot.anthropic.api-key", "sk-test-not-real");
        values.put("shf.copilot.anthropic.base-url", baseUrl);
        return values;
    }

    @Test
    void baseUrlMustBeHttpsOnTheOfficialHostAndNeverEchoesTheKey() {
        for (String rejected : new String[] {"http://api.anthropic.com", "https://evil.example.com",
                "https://api.anthropic.com.evil.example", "https://user:pass@api.anthropic.com", "http://127.0.0.1:8080",
                "nao é url", "https://api.anthropic.com:8443", "https://api.anthropic.com:444",
                "https://api.anthropic.com/x"}) {
            assertThatThrownBy(() -> configuration.copilotAiProvider(bind(anthropic(rejected)), json))
                    .as(rejected).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base-url")
                    .message().doesNotContain("sk-test-not-real");
        }
        assertThat(configuration.copilotAiProvider(bind(anthropic("https://api.anthropic.com")), json))
                .isInstanceOf(AnthropicAiProvider.class);
        assertThat(configuration.copilotAiProvider(bind(anthropic("")), json)).isInstanceOf(AnthropicAiProvider.class);
    }

    @Test
    void anotherHostNeedsTheExplicitTestProperty() {
        Map<String, String> values = anthropic("http://127.0.0.1:8080");
        values.put("shf.copilot.anthropic.allow-custom-base-url", "true");

        assertThat(configuration.copilotAiProvider(bind(values), json)).isInstanceOf(AnthropicAiProvider.class);
    }

    @Test
    void unknownProviderIsRejected() {
        assertThatThrownBy(() -> configuration.copilotAiProvider(bind(Map.of("shf.copilot.provider", "other")), json))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void provisionalLimitsAreTheApprovedOnes() {
        var limits = configuration.copilotLimits(bind(Map.of()));

        assertThat(limits.dailyQuestions()).isEqualTo(10);
        assertThat(limits.monthlyQuestions()).isEqualTo(100);
        assertThat(limits.monthlyInputTokens()).isEqualTo(1_000_000);
        assertThat(limits.bucketCapacity()).isEqualTo(3);
        assertThat(limits.refillInterval()).hasSeconds(20);
    }
}
