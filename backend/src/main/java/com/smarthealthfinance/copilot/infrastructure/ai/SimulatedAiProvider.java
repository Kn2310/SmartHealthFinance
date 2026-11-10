package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Provedor simulado e determinístico (ADR-0039 §2): padrão no local, nos testes e na CI; nunca acessa a rede. A
 * resposta vem de um {@code responder} (a Parte C liga o que monta a saída a partir do próprio contexto); tokens são
 * estimados por caracteres (÷4), de forma reprodutível. {@code mode} simula as falhas do provedor real.
 */
public final class SimulatedAiProvider implements AiProvider {

    public static final String NAME = "simulated";
    public static final String MODEL = "simulated-v1";

    /** Comportamento configurável ({@code shf.copilot.simulated.mode}). */
    public enum Mode { OK, REFUSED, INVALID_OUTPUT, TIMEOUT, RATE_LIMITED, UNAVAILABLE }

    /** Resposta padrão: um objeto vazio, válido para qualquer esquema sem campos obrigatórios. */
    public static final BiFunction<AiRole, AiRequest, String> EMPTY_OBJECT = (role, request) -> "{}";

    private final Mode mode;
    private final BiFunction<AiRole, AiRequest, String> responder;

    public SimulatedAiProvider(Mode mode, BiFunction<AiRole, AiRequest, String> responder) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.responder = Objects.requireNonNull(responder, "responder");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String model(AiRole role) {
        return MODEL;
    }

    @Override
    public Reply call(AiRole role, AiRequest request) {
        TokenUsage input = new TokenUsage(estimate(request.systemPrompt()) + estimate(request.userContent()), 0, 0, 0);
        return switch (mode) {
            case TIMEOUT -> throw new AiProviderException(AiOutcome.TIMEOUT, false, "simulated timeout");
            case RATE_LIMITED -> throw new AiProviderException(AiOutcome.RATE_LIMITED, true, "simulated 429");
            case UNAVAILABLE -> throw new AiProviderException(AiOutcome.UNAVAILABLE, true, "simulated 503");
            case REFUSED -> new Reply(AiOutcome.REFUSED, null, MODEL, input);
            case INVALID_OUTPUT -> new Reply(AiOutcome.INVALID_OUTPUT, null, MODEL, input);
            case OK -> {
                String output = responder.apply(role, request);
                yield new Reply(AiOutcome.OK, output, MODEL,
                        new TokenUsage(input.input(), estimate(output), 0, 0));
            }
        };
    }

    static long estimate(String text) {
        return (text.length() + 3) / 4;
    }
}
