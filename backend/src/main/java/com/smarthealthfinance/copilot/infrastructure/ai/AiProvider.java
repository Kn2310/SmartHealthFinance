package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;

import java.util.Objects;

/**
 * Provedor concreto atrás do {@link ResilientAiGateway} (ADR-0039 §2). Falha de transporte, 429 e 5xx viram
 * {@link AiProviderException} (contam para o circuito); recusa e saída inutilizável voltam como {@link Reply}, porque
 * o provedor respondeu.
 */
public interface AiProvider {

    String name();

    String model(AiRole role);

    /** @throws AiProviderException em falha de transporte ou do provedor */
    Reply call(AiRole role, AiRequest request);

    /** {@code outcome}: OK, REFUSED ou INVALID_OUTPUT; {@code json} só com OK. */
    record Reply(AiOutcome outcome, String json, String model, TokenUsage usage) {
        public Reply {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(usage, "usage");
            if (outcome != AiOutcome.OK && outcome != AiOutcome.REFUSED && outcome != AiOutcome.INVALID_OUTPUT) {
                throw new IllegalArgumentException("a reply is OK, REFUSED or INVALID_OUTPUT");
            }
            if ((outcome == AiOutcome.OK) != (json != null)) {
                throw new IllegalArgumentException("json is present only on OK");
            }
        }

        @Override
        public String toString() {
            return "Reply[outcome=" + outcome + ", model=" + model + ", usage=" + usage + "]";
        }
    }
}
