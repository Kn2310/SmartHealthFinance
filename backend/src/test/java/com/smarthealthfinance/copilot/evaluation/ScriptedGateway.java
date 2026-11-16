package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.application.port.AiResult;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Provedor de IA do CI (ADR-0042 §2): devolve, na ordem, a saída roteirizada do caso. É o adapter simulado do golden
 * set: determinístico, sem rede e sem chave. Guarda o que o pipeline enviou ao modelo, para as invariantes de
 * privacidade e de injeção. Pedir mais chamadas do que o roteiro tem é erro do caso, não do pipeline.
 */
final class ScriptedGateway implements AiGateway {

    static final String PROVIDER = "scripted";
    static final String INTENT_MODEL = "golden-intent";
    static final String ANSWER_MODEL = "golden-answer";

    final List<AiRequest> intentRequests = new ArrayList<>();
    final List<AiRequest> answerRequests = new ArrayList<>();
    private final String caseId;
    private final Deque<JsonNode> intents = new ArrayDeque<>();
    private final Deque<JsonNode> answers = new ArrayDeque<>();

    ScriptedGateway(GoldenCase golden) {
        this.caseId = golden.id();
        if (golden.scripted() != null) {
            if (golden.scripted().intent() != null) {
                intents.add(golden.scripted().intent());
            }
            answers.addAll(golden.scripted().answers());
        }
    }

    @Override
    public AiResult classify(AiRequest request) {
        intentRequests.add(request);
        return next(intents, INTENT_MODEL, 400, "intent");
    }

    @Override
    public AiResult answer(AiRequest request) {
        answerRequests.add(request);
        return next(answers, ANSWER_MODEL, 1_500, "answer");
    }

    private AiResult next(Deque<JsonNode> script, String model, long inputTokens, String role) {
        JsonNode step = script.pollFirst();
        if (step == null) {
            throw new IllegalStateException("case " + caseId + " made one more " + role + " call than it scripts");
        }
        TokenUsage usage = new TokenUsage(inputTokens, 60, 0, 0);
        if (step.isObject() && step.size() == 1 && step.has("outcome")) {
            return AiResult.failed(AiOutcome.valueOf(step.get("outcome").asString()), PROVIDER, model, TokenUsage.NONE,
                    Duration.ofMillis(5));
        }
        String json = step.isObject() && step.size() == 1 && step.has("raw") ? step.get("raw").asString()
                : step.toString();
        return AiResult.ok(json, PROVIDER, model, usage, Duration.ofMillis(5));
    }

    /** Sobra de roteiro: o caso previu uma chamada que o pipeline não fez. */
    List<String> unusedSteps() {
        List<String> unused = new ArrayList<>();
        if (!intents.isEmpty()) {
            unused.add("intent x" + intents.size());
        }
        if (!answers.isEmpty()) {
            unused.add("answer x" + answers.size());
        }
        return unused;
    }
}
