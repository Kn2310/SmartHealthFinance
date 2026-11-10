package com.smarthealthfinance.copilot.infrastructure.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Adapter da Claude API pelo SDK oficial (ADR-0039 §1, §2): um cliente por papel (timeouts próprios), sem retries do
 * SDK (o retry é do {@link ResilientAiGateway}), saída estruturada por {@code output_config.format}, esforço baixo e o
 * prompt de sistema em cache. Não usa recursos com estado no provedor. Nunca loga prompt, contexto nem resposta: só
 * status HTTP e tipo de erro.
 */
public final class AnthropicAiProvider implements AiProvider {

    public static final String NAME = "anthropic";
    private static final Logger log = LoggerFactory.getLogger(AnthropicAiProvider.class);
    private static final TypeReference<Map<String, Object>> SCHEMA = new TypeReference<>() {
    };

    /** Um papel: cliente com timeout próprio, modelo e teto de saída. */
    public record Route(AnthropicClient client, String model, long maxOutputTokens) {
        public Route {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(model, "model");
        }
    }

    private final Route intent;
    private final Route answer;
    private final OutputConfig.Effort effort;
    private final JsonMapper json;

    public AnthropicAiProvider(Route intent, Route answer, OutputConfig.Effort effort, JsonMapper json) {
        this.intent = Objects.requireNonNull(intent, "intent");
        this.answer = Objects.requireNonNull(answer, "answer");
        this.effort = Objects.requireNonNull(effort, "effort");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String model(AiRole role) {
        return route(role).model();
    }

    @Override
    public Reply call(AiRole role, AiRequest request) {
        Route route = route(role);
        Message message;
        try {
            message = route.client().messages().create(params(route, request));
        } catch (AnthropicServiceException e) {
            throw translate(e);
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            boolean timeout = causedByReadTimeout(e);
            log.warn("Copilot provider transport failure provider={} role={} timeout={}", NAME, role, timeout);
            throw new AiProviderException(timeout ? AiOutcome.TIMEOUT : AiOutcome.UNAVAILABLE, !timeout,
                    timeout ? "timeout" : "transport");
        }
        return reply(route, message);
    }

    MessageCreateParams params(Route route, AiRequest request) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        json.readValue(request.jsonSchema(), SCHEMA)
                .forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        return MessageCreateParams.builder()
                .model(route.model())
                .maxTokens(route.maxOutputTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(request.userContent())
                .outputConfig(OutputConfig.builder()
                        .effort(effort)
                        .format(JsonOutputFormat.builder().schema(schema.build()).build())
                        .build())
                .build();
    }

    private Reply reply(Route route, Message message) {
        String model = route.model();
        TokenUsage usage = usage(message.usage());
        StopReason stop = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            return new Reply(AiOutcome.REFUSED, null, model, usage);
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining());
        if (StopReason.MAX_TOKENS.equals(stop) || StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stop)
                || text.isBlank()) {
            return new Reply(AiOutcome.INVALID_OUTPUT, null, model, usage);
        }
        return new Reply(AiOutcome.OK, text, model, usage);
    }

    static TokenUsage usage(Usage usage) {
        return new TokenUsage(usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
    }

    /** 429 e 5xx (inclui 529 overloaded) são repetíveis; 408 também; o resto (chave inválida, 400) não. */
    static AiProviderException translate(AnthropicServiceException e) {
        int status = e.statusCode();
        log.warn("Copilot provider error provider={} status={} type={}", NAME, status,
                e.errorType().map(Object::toString).orElse("unknown"));
        if (status == 429) {
            return new AiProviderException(AiOutcome.RATE_LIMITED, true, "status 429");
        }
        boolean retryable = status >= 500 || status == 408;
        return new AiProviderException(AiOutcome.UNAVAILABLE, retryable, "status " + status);
    }

    /**
     * Timeout de leitura (o provedor recebeu e não respondeu a tempo): não repetível. Timeout de conexão ("connect
     * timed out") é falha de conexão e continua repetível, como o 5xx.
     */
    static boolean causedByReadTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) {
                String message = cause.getMessage();
                return message == null || !message.toLowerCase(java.util.Locale.ROOT).contains("connect");
            }
        }
        return false;
    }

    private Route route(AiRole role) {
        return role == AiRole.INTENT ? intent : answer;
    }
}
