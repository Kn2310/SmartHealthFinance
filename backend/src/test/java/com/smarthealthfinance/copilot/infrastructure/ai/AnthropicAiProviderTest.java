package com.smarthealthfinance.copilot.infrastructure.ai;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.OutputConfig;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter da Claude API (ADR-0039 §1, §2) contra um servidor HTTP falso do JDK em 127.0.0.1: sem rede, sem chave real.
 * Confere o corpo enviado (modelo, saída estruturada, esforço, cache do system prompt) e o mapeamento de respostas e
 * erros.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicAiProviderTest {

    private static final String FAKE_KEY = "test-key-not-real";
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"intent\":{\"type\":\"string\"}},\"required\":[\"intent\"],"
                    + "\"additionalProperties\":false}";
    private static final AiRequest REQUEST =
            new AiRequest("Você classifica perguntas.", "Quanto gastei em setembro?", SCHEMA);

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> apiKeys = new ArrayList<>();
    private HttpServer server;

    @FunctionalInterface
    interface Responder {
        Response respond() throws Exception;
    }

    record Response(int status, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // Registra como o SDK manda a credencial; o teste afirma o formato exato da versão fixada.
            String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
            String authorization = exchange.getRequestHeaders().getFirst("authorization");
            apiKeys.add("x-api-key=" + apiKey + "; authorization=" + authorization);
            Response response;
            try {
                response = responder.get().respond();
            } catch (Exception e) {
                response = new Response(500, "{}");
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicAiProvider provider(Duration timeout) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(FAKE_KEY).baseUrl(baseUrl).maxRetries(0).timeout(timeout);
        return new AnthropicAiProvider(
                new AnthropicAiProvider.Route(builder.build(), "claude-haiku-5-5", 1024),
                new AnthropicAiProvider.Route(builder.build(), "claude-sonnet-5-5", 4096),
                OutputConfig.Effort.LOW, json);
    }

    private static String message(String model, String text, String stopReason) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"%s",
                 "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":%s}],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":120,"output_tokens":30,"cache_read_input_tokens":800,
                          "cache_creation_input_tokens":0}}
                """.formatted(model, quote(text), stopReason);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String error(String type) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"detalhe do provedor\"}}";
    }

    @Test
    void sendsStructuredOutputLowEffortAndCachedSystemPromptAndMapsTheReply() {
        responder.set(() -> new Response(200, message("claude-haiku-5-5", "{\"intent\":\"CASH_FLOW\"}", "end_turn")));

        AiProvider.Reply reply = provider(Duration.ofSeconds(5)).call(AiRole.INTENT, REQUEST);

        assertThat(reply.outcome()).isEqualTo(AiOutcome.OK);
        assertThat(reply.json()).isEqualTo("{\"intent\":\"CASH_FLOW\"}");
        assertThat(reply.model()).isEqualTo("claude-haiku-5-5");
        assertThat(reply.usage()).isEqualTo(new TokenUsage(120, 30, 800, 0));

        JsonNode body = json.readTree(bodies.getFirst());
        assertThat(body.path("model").asString()).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(1024);
        assertThat(body.path("output_config").path("effort").asString()).isEqualTo("low");
        assertThat(body.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("required").get(0).asString())
                .isEqualTo("intent");
        assertThat(body.path("output_config").path("format").path("schema").path("additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(body.path("system").get(0).path("text").asString()).isEqualTo("Você classifica perguntas.");
        assertThat(body.path("system").get(0).path("cache_control").path("type").asString()).isEqualTo("ephemeral");
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
        assertThat(body.has("thread")).isFalse();
        assertThat(body.has("tools")).isFalse();
        // anthropic-java 3.7.0 com .apiKey(...) manda Authorization: Bearer <chave> e não manda x-api-key. Se uma
        // versão nova do SDK mudar isso, este teste falha e o smoke test com a chave real deve ser refeito (runbook).
        assertThat(apiKeys).containsOnly("x-api-key=null; authorization=Bearer " + FAKE_KEY);
    }

    @Test
    void answerUsesTheAnswerModelAndItsOutputCeiling() {
        responder.set(() -> new Response(200, message("claude-sonnet-5-5", "{}", "end_turn")));

        provider(Duration.ofSeconds(5)).call(AiRole.ANSWER, REQUEST);

        JsonNode body = json.readTree(bodies.getFirst());
        assertThat(body.path("model").asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(4096);
    }

    @Test
    void refusalAndTruncatedOutputAreNotOk() {
        AnthropicAiProvider provider = provider(Duration.ofSeconds(5));

        responder.set(() -> new Response(200, message("claude-sonnet-5-5", "", "refusal")));
        assertThat(provider.call(AiRole.ANSWER, REQUEST).outcome()).isEqualTo(AiOutcome.REFUSED);

        responder.set(() -> new Response(200, message("claude-sonnet-5-5", "{\"answer\":\"cort", "max_tokens")));
        AiProvider.Reply truncated = provider.call(AiRole.ANSWER, REQUEST);
        assertThat(truncated.outcome()).isEqualTo(AiOutcome.INVALID_OUTPUT);
        assertThat(truncated.json()).isNull();
        assertThat(truncated.usage().output()).isEqualTo(30);
    }

    @Test
    void rateLimitIsRetryable() {
        responder.set(() -> new Response(429, error("rate_limit_error")));

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).call(AiRole.ANSWER, REQUEST))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(AiOutcome.RATE_LIMITED);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void serverErrorsAndOverloadAreRetryableUnavailable() {
        for (int status : new int[] {500, 503, 529}) {
            responder.set(() -> new Response(status, error("overloaded_error")));

            assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).call(AiRole.ANSWER, REQUEST))
                    .isInstanceOfSatisfying(AiProviderException.class, e -> {
                        assertThat(e.outcome()).isEqualTo(AiOutcome.UNAVAILABLE);
                        assertThat(e.retryable()).isTrue();
                    });
        }
    }

    @Test
    void invalidKeyIsUnavailableNotRetriedAndLogsNoSecretOrContent(CapturedOutput output) {
        responder.set(() -> new Response(401, error("authentication_error")));

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).call(AiRole.ANSWER, REQUEST))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(AiOutcome.UNAVAILABLE);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("status 401");
                });
        assertThat(output).contains("status=401")
                .doesNotContain(FAKE_KEY)
                .doesNotContain("Quanto gastei")
                .doesNotContain("detalhe do provedor");
    }

    @Test
    void connectTimeoutIsAConnectionFailureNotAReadTimeout() {
        assertThat(AnthropicAiProvider.causedByReadTimeout(
                new RuntimeException(new java.net.SocketTimeoutException("timeout")))).isTrue();
        assertThat(AnthropicAiProvider.causedByReadTimeout(
                new RuntimeException(new java.net.SocketTimeoutException("Read timed out")))).isTrue();
        assertThat(AnthropicAiProvider.causedByReadTimeout(
                new RuntimeException(new java.net.SocketTimeoutException("connect timed out")))).isFalse();
        assertThat(AnthropicAiProvider.causedByReadTimeout(
                new RuntimeException(new java.net.ConnectException("Connection refused")))).isFalse();
    }

    @Test
    void slowResponseIsATimeoutThatIsNotRetried() {
        responder.set(() -> {
            Thread.sleep(1_500);
            return new Response(200, message("claude-sonnet-5-5", "{}", "end_turn"));
        });

        assertThatThrownBy(() -> provider(Duration.ofMillis(300)).call(AiRole.ANSWER, REQUEST))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(AiOutcome.TIMEOUT);
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void unreachableProviderIsRetryableUnavailable() {
        AnthropicAiProvider provider = provider(Duration.ofSeconds(2));
        server.stop(0);

        assertThatThrownBy(() -> provider.call(AiRole.ANSWER, REQUEST))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.outcome()).isIn(AiOutcome.UNAVAILABLE, AiOutcome.TIMEOUT);
                });
    }
}
