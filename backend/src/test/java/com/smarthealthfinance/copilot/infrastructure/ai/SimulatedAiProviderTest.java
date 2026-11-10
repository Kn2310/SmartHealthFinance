package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provedor simulado (ADR-0039 §2): determinístico, sem rede, com as falhas do provedor real. */
class SimulatedAiProviderTest {

    private static final AiRequest REQUEST = new AiRequest("12345678", "1234", "{}");

    @Test
    void okIsDeterministicAndEstimatesTokens() {
        SimulatedAiProvider provider = new SimulatedAiProvider(SimulatedAiProvider.Mode.OK,
                (role, request) -> "{\"role\":\"" + role + "\"}");

        AiProvider.Reply first = provider.call(AiRole.INTENT, REQUEST);
        AiProvider.Reply second = provider.call(AiRole.INTENT, REQUEST);

        assertThat(first).isEqualTo(second);
        assertThat(first.outcome()).isEqualTo(AiOutcome.OK);
        assertThat(first.json()).isEqualTo("{\"role\":\"INTENT\"}");
        // (8+3)/4 + (4+3)/4 = 2 + 1; saída de 17 caracteres → 5
        assertThat(first.usage().input()).isEqualTo(3);
        assertThat(first.usage().output()).isEqualTo(5);
        assertThat(first.model()).isEqualTo(SimulatedAiProvider.MODEL);
        assertThat(provider.name()).isEqualTo("simulated");
    }

    @Test
    void defaultResponderIsAnEmptyObject() {
        assertThat(new SimulatedAiProvider(SimulatedAiProvider.Mode.OK, SimulatedAiProvider.EMPTY_OBJECT)
                .call(AiRole.ANSWER, REQUEST).json()).isEqualTo("{}");
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT,TIMEOUT,false", "RATE_LIMITED,RATE_LIMITED,true", "UNAVAILABLE,UNAVAILABLE,true"})
    void failureModesThrowLikeTheRealProvider(SimulatedAiProvider.Mode mode, AiOutcome outcome, boolean retryable) {
        SimulatedAiProvider provider = new SimulatedAiProvider(mode, SimulatedAiProvider.EMPTY_OBJECT);

        assertThatThrownBy(() -> provider.call(AiRole.ANSWER, REQUEST))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(outcome);
                    assertThat(e.retryable()).isEqualTo(retryable);
                });
    }

    @ParameterizedTest
    @CsvSource({"REFUSED,REFUSED", "INVALID_OUTPUT,INVALID_OUTPUT"})
    void replyModesAnswerWithoutJson(SimulatedAiProvider.Mode mode, AiOutcome outcome) {
        AiProvider.Reply reply = new SimulatedAiProvider(mode, SimulatedAiProvider.EMPTY_OBJECT)
                .call(AiRole.ANSWER, REQUEST);

        assertThat(reply.outcome()).isEqualTo(outcome);
        assertThat(reply.json()).isNull();
    }
}
