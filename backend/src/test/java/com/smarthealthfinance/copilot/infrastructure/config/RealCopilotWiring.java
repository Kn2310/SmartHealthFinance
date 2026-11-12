package com.smarthealthfinance.copilot.infrastructure.config;

import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.domain.model.PricingTable;
import com.smarthealthfinance.copilot.infrastructure.ai.AiProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

/**
 * Liga o adapter Anthropic de verdade para a avaliação {@code copilot-eval} (ADR-0042 §3), com a mesma configuração
 * de produção ({@code application.yml}: modelos, timeouts, preços, resiliência) e o mesmo {@link CopilotConfiguration}.
 * Fica neste pacote só para alcançar os beans de pacote. A chave vem <b>somente</b> da variável de ambiente
 * {@code ANTHROPIC_API_KEY}; sem ela falha com uma mensagem clara e nunca cai no provedor simulado. A chave nunca é
 * impressa nem guardada no relatório.
 */
public final class RealCopilotWiring {

    public static final String KEY_VARIABLE = "ANTHROPIC_API_KEY";
    public static final String MISSING_KEY = "copilot-eval needs " + KEY_VARIABLE + " in the environment (never "
            + "in a file). It is not set, so nothing ran: this profile never falls back to the simulated adapter. "
            + "Set the variable (see docs/runbooks/copilot-evaluation.md) and run again.";

    /** O que a avaliação usa: tabela de preços, modelos configurados e uma fábrica de gateway por caso. */
    public record Wiring(PricingTable pricing, String intentModel, String answerModel,
                         Function<MeterRegistry, AiGateway> gateways) {
    }

    private RealCopilotWiring() {
    }

    /** @throws IllegalStateException sem {@code ANTHROPIC_API_KEY} no ambiente */
    public static Wiring anthropic() {
        String key = System.getenv(KEY_VARIABLE);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(MISSING_KEY);
        }
        return anthropic(key);
    }

    /** Monta o adapter com a chave dada (o teste usa uma chave de mentira; a avaliação, a do ambiente). */
    public static Wiring anthropic(String key) {
        CopilotProperties configured = properties();
        CopilotProperties.Anthropic anthropic = configured.anthropic();
        CopilotProperties properties = new CopilotProperties(true, "anthropic", configured.zone(), configured.models(),
                new CopilotProperties.Anthropic(key, anthropic.baseUrl(), anthropic.intentTimeout(),
                        anthropic.answerTimeout(), anthropic.allowCustomBaseUrl()),
                configured.simulated(), configured.resilience(), configured.limits(), configured.pricing());
        CopilotConfiguration configuration = new CopilotConfiguration();
        AiProvider provider = configuration.copilotAiProvider(properties, JsonMapper.builder().build());
        return new Wiring(configuration.copilotPricingTable(properties), properties.models().intent(),
                properties.models().answer(), meters -> configuration.copilotAiGateway(provider, properties, meters));
    }

    /** {@code shf.copilot.*} do {@code application.yml}, com os placeholders resolvidos como em produção. */
    private static CopilotProperties properties() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
                    new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Binder.get(environment).bindOrCreate("shf.copilot", CopilotProperties.class);
    }
}
