package com.smarthealthfinance.copilot;

import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.infrastructure.ai.AiProvider;
import com.smarthealthfinance.copilot.infrastructure.ai.AiProviderException;
import com.smarthealthfinance.copilot.infrastructure.ai.ResilientAiGateway;
import com.smarthealthfinance.copilot.infrastructure.ai.SimulatedAiProvider;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Falha de IA nunca afeta o núcleo financeiro (ADR-0039 §2, 05.8 "Falha"): com o provedor fora e o circuito aberto,
 * o gateway só devolve {@code UNAVAILABLE}, e a API financeira e a readiness seguem normais. Também garante que a
 * aplicação de teste usa o provedor simulado (CI nunca chama a rede).
 */
class CopilotFailureIsolationIT extends IntegrationTest {

    @Autowired
    AiProvider provider;

    @Autowired
    AiGateway gateway;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        IdentityTables.clean(jdbc);
    }

    @Test
    void testsAndCiAlwaysRunOnTheSimulatedProvider() {
        assertThat(provider).isInstanceOf(SimulatedAiProvider.class);
        assertThat(provider.model(AiRole.ANSWER)).isEqualTo(SimulatedAiProvider.MODEL);
        assertThat(gateway.answer(new AiRequest("s", "u", "{}")).outcome()).isEqualTo(AiOutcome.OK);
    }

    @Test
    void providerOutageOpensTheCircuitAndTheFinancialCoreKeepsWorking() throws Exception {
        AiProvider down = new AiProvider() {
            @Override
            public String name() {
                return "down";
            }

            @Override
            public String model(AiRole role) {
                return "m";
            }

            @Override
            public Reply call(AiRole role, AiRequest request) {
                throw new AiProviderException(AiOutcome.UNAVAILABLE, true, "simulated outage");
            }
        };
        CircuitBreaker breaker = CircuitBreaker.of("isolation", CircuitBreakerConfig.custom()
                .slidingWindowSize(5).minimumNumberOfCalls(5).waitDurationInOpenState(Duration.ofMinutes(1)).build());
        ResilientAiGateway failing = new ResilientAiGateway(down, breaker, Retry.ofDefaults("isolation"),
                Bulkhead.ofDefaults("isolation"), meters, System::nanoTime);

        for (int i = 0; i < 10; i++) {
            assertThat(failing.answer(new AiRequest("s", "u", "{}")).outcome()).isEqualTo(AiOutcome.UNAVAILABLE);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        String workspace = com.jayway.jsonpath.JsonPath.read(mvc.post().uri("/api/v1/users/me").with(ana()).exchange()
                .getResponse().getContentAsString(StandardCharsets.UTF_8), "$.workspaceId");
        assertThat(mvc.post().uri("/api/v1/workspaces/{id}/accounts", workspace).with(ana())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Conta\", \"type\": \"CHECKING\"}")).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.get().uri("/api/v1/workspaces/{id}/overview", workspace).with(ana())).hasStatus(HttpStatus.OK);
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatus(HttpStatus.OK);
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(t -> t.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Ana"));
    }
}
