package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de uma chamada (ADR-0039 §2). {@code json} só existe com {@code OK}: o texto da saída estruturada, ainda
 * não validado (a validação é do pipeline). {@code usage} e {@code latency} vêm sempre, para o ledger.
 */
public record AiResult(AiOutcome outcome, String json, String provider, String model, TokenUsage usage,
                       Duration latency) {

    public AiResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(latency, "latency");
        if ((outcome == AiOutcome.OK) != (json != null)) {
            throw new IllegalArgumentException("json is present only on OK");
        }
    }

    public static AiResult ok(String json, String provider, String model, TokenUsage usage, Duration latency) {
        return new AiResult(AiOutcome.OK, json, provider, model, usage, latency);
    }

    public static AiResult failed(AiOutcome outcome, String provider, String model, TokenUsage usage,
                                  Duration latency) {
        return new AiResult(outcome, null, provider, model, usage, latency);
    }

    public Optional<String> output() {
        return Optional.ofNullable(json);
    }

    /** Sem conteúdo: a saída do modelo não vai para logs. */
    @Override
    public String toString() {
        return "AiResult[outcome=" + outcome + ", model=" + model + ", usage=" + usage + ", latency=" + latency + "]";
    }
}
