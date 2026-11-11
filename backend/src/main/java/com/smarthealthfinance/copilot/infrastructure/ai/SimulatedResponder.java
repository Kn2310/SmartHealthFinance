package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Respostas do provedor simulado (ADR-0039 §2), determinísticas e montadas só a partir do próprio pedido: a intenção
 * vem de palavras-chave da pergunta; a resposta cita os primeiros valores do contexto por referência. Sempre válidas
 * nos esquemas {@code intents-v1} e {@code answer-v1} e na validação de claims. Nunca acessa a rede.
 */
public final class SimulatedResponder {

    /** Quantos valores a resposta simulada cita. */
    static final int CITED = 3;

    private static final Pattern QUESTION = Pattern.compile("<pergunta>(.*?)</pergunta>", Pattern.DOTALL);
    private static final Pattern DATA = Pattern.compile("<dados_financeiros>(.*?)</dados_financeiros>",
            Pattern.DOTALL);

    /** Palavras-chave por intenção, na ordem em que são testadas (sem acento, minúsculas). */
    private static final List<Map.Entry<String, Pattern>> KEYWORDS = List.of(
            Map.entry("UNSUPPORTED_ACTION", words("transfir|transfere|pague|pagar|apague|apagar|edite|editar|crie")),
            Map.entry("GOAL_SIMULATION", words("simul")),
            Map.entry("GOALS", words("objetivo|meta")),
            Map.entry("HEALTH", words("saude|score")),
            Map.entry("INSIGHTS", words("insight|alerta")),
            Map.entry("CARDS", words("fatura|cartao|parcel")),
            Map.entry("FORECAST", words("previs|proxim")),
            Map.entry("MERCHANT", words("comerciante|loja")),
            Map.entry("CATEGORY", words("categoria")),
            Map.entry("CASH_FLOW", words("gast|receit|sobr")),
            Map.entry("OVERVIEW", words("saldo|visao|resumo")));

    private final JsonMapper json;

    public SimulatedResponder(JsonMapper json) {
        this.json = json;
    }

    public String respond(AiRole role, AiRequest request) {
        return role == AiRole.INTENT ? intent(request.userContent()) : answer(request.userContent());
    }

    private String intent(String content) {
        String question = normalized(block(QUESTION, content));
        String intent = KEYWORDS.stream()
                .filter(entry -> entry.getValue().matcher(question).find())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("OUT_OF_SCOPE");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", intent);
        out.put("month", null);
        out.put("category", null);
        out.put("target", null);
        out.put("scenario", null);
        return json.writeValueAsString(out);
    }

    private String answer(String content) {
        List<String> refs = new ArrayList<>();
        List<Map<String, Object>> cited = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        try {
            JsonNode data = json.readTree(block(DATA, content));
            for (JsonNode value : data.get("values")) {
                if (refs.size() == CITED) {
                    break;
                }
                refs.add(value.get("ref").asString());
                cited.add(Map.of("ref", value.get("ref").asString(), "label", value.get("label").asString(),
                        "kind", value.get("kind").asString()));
            }
            for (JsonNode action : data.get("actions")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", action.get("kind").asString());
                item.put("target", action.get("target").isNull() ? null : action.get("target").asString());
                actions.add(item);
                break;
            }
            for (JsonNode source : data.get("sources")) {
                sources.add(Map.of("tool", source.get("tool").asString(), "version",
                        source.get("version").asString()));
            }
        } catch (JacksonException | NullPointerException e) {
            refs.clear();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", refs.isEmpty() ? "Não encontrei dados para responder."
                : "Segundo os dados do app: " + String.join("; ", refs.stream().map(ref -> "{{v:" + ref + "}}")
                .toList()) + ".");
        out.put("type", refs.isEmpty() ? "insufficient_data" : "analysis");
        out.put("financialData", cited);
        out.put("explanation", List.of());
        out.put("confidence", "medium");
        out.put("actions", actions);
        out.put("sources", sources);
        return json.writeValueAsString(out);
    }

    private static String block(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static Pattern words(String alternatives) {
        return Pattern.compile("(" + alternatives + ")");
    }
}
