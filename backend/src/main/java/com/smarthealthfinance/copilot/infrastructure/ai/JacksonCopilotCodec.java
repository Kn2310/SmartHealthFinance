package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.CopilotCodec;
import com.smarthealthfinance.copilot.application.port.RequestCatalog;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Serialização do que vai ao modelo e leitura estrita do que volta (ADR-0039 §5, §6, §8). Pergunta e dados vão como
 * JSON (strings escapadas) dentro de blocos delimitados, então nenhum texto da pessoa ou de importação fecha um bloco
 * nem vira instrução. A leitura devolve vazio para qualquer saída fora do esquema.
 */
@Component
public class JacksonCopilotCodec implements CopilotCodec {

    /** Dica de correção por código de violação, enviada na regeneração (§7.3). Nunca o texto rejeitado. */
    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("UNKNOWN_REF", "Você citou uma referência que não existe nos dados. Use só refs de `valores`."),
            Map.entry("UNKNOWN_PSEUDONYM", "Você citou um pseudônimo que não existe. Use só os dos dados."),
            Map.entry("DIGIT_OUTSIDE_REF", "Você escreveu dígitos. Troque todo número por {{v:REF}}."),
            Map.entry("NUMBER_WORD", "Você escreveu um número por extenso, uma quantidade (\"metade\", \"dobro\"), "
                    + "\"reais\", \"centavos\" ou \"por cento\". Use {{v:REF}}."),
            Map.entry("SYMBOL_OUTSIDE_REF", "Você escreveu R$ ou %. O app formata os valores; use só {{v:REF}}."),
            Map.entry("MALFORMED_REF", "Uma referência está malformada. O formato é {{v:v1}}."),
            Map.entry("UNKNOWN_TYPE", "O campo type está fora do esquema."),
            Map.entry("EMPTY_ANSWER", "O campo answer veio vazio."),
            Map.entry("ANSWER_TOO_LONG", "A resposta ficou longa demais. Use até cinco frases."),
            Map.entry("STEP_TOO_LONG", "Um passo de explanation ficou longo demais."),
            Map.entry("TOO_MANY_STEPS", "Use no máximo seis passos em explanation."),
            Map.entry("SCHEMA", "A saída não seguiu o esquema JSON."));

    private final JsonMapper json;

    public JacksonCopilotCodec(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String intentInput(String question, List<String> previousQuestions, LocalDate today,
                              RequestCatalog.Catalog catalog) {
        List<Map<String, String>> categories = new TreeMap<>(catalog.categoryNames()).entrySet().stream()
                .map(entry -> Map.of("codigo", entry.getKey(), "nome", entry.getValue()))
                .toList();
        List<Map<String, String>> targets = catalog.targets().stream()
                .map(target -> Map.of("pseudonimo", target.pseudonym(), "descricao", target.description()))
                .toList();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("categorias", categories);
        block.put("alvos", targets);
        return "<hoje>" + today + "</hoje>\n<catalogo>" + jsonText(block) + "</catalogo>\n"
                + previous(previousQuestions) + "<pergunta>" + jsonText(question) + "</pergunta>";
    }

    @Override
    public String answerInput(String question, List<String> previousQuestions, CopilotContext context,
                              List<String> previousViolations) {
        StringBuilder out = new StringBuilder(previous(previousQuestions)).append("<pergunta>")
                .append(jsonText(question)).append("</pergunta>\n<dados_financeiros>")
                .append(jsonText(context(context)))
                .append("</dados_financeiros>");
        if (!previousViolations.isEmpty()) {
            out.append("\n<correcao>");
            previousViolations.forEach(code -> out.append("\n- ").append(HINTS.getOrDefault(code, HINTS.get("SCHEMA"))));
            out.append("\n</correcao>");
        }
        return out.toString();
    }

    /** O contexto {@code context-v1} como vai ao modelo. Público para o provedor simulado e os testes. */
    public Map<String, Object> context(CopilotContext context) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", CopilotContext.VERSION);
        root.put("intent", context.intent().name());
        root.put("today", context.today().toString());
        root.put("currency", context.currency().getCurrencyCode());
        List<Map<String, Object>> values = new ArrayList<>();
        for (ContextValue value : context.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", value.ref());
            item.put("label", value.label());
            item.put("value", value.value());
            item.put("unit", value.unit().name());
            item.put("kind", value.kind().name());
            item.put("period", value.period());
            item.put("source", value.source());
            values.add(item);
        }
        root.put("values", values);
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("monthsWithData", context.quality().monthsWithData());
        quality.put("partialPeriod", context.quality().partialPeriod());
        quality.put("gaps", context.quality().gaps());
        quality.put("freshness", context.quality().freshness().name());
        quality.put("sources", context.quality().sources().stream().sorted().toList());
        root.put("dataQuality", quality);
        root.put("sources", context.sources().stream()
                .map((SourceRef source) -> Map.of("tool", source.tool(), "version", source.version())).toList());
        List<Map<String, Object>> actions = new ArrayList<>();
        for (OfferedAction action : context.actions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", action.kind().name());
            item.put("target", action.target());
            actions.add(item);
        }
        root.put("actions", actions);
        return root;
    }

    @Override
    public Optional<RawIntent> readIntent(String output) {
        try {
            JsonNode root = json.readTree(output);
            if (!root.isObject() || !string(root.get("intent"))) {
                return Optional.empty();
            }
            JsonNode scenario = root.get("scenario");
            boolean hasScenario = scenario != null && scenario.isObject();
            return Optional.of(new RawIntent(root.get("intent").asString(), text(root.get("month")),
                    text(root.get("category")), text(root.get("target")),
                    hasScenario ? text(scenario.get("monthlyAmount")) : null,
                    hasScenario ? text(scenario.get("deadline")) : null,
                    hasScenario ? text(scenario.get("reductionCategory")) : null,
                    hasScenario ? text(scenario.get("reductionAmount")) : null));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AnswerDraft> readAnswer(String output) {
        try {
            JsonNode root = json.readTree(output);
            if (!root.isObject() || !string(root.get("answer")) || !string(root.get("type"))
                    || !array(root.get("financialData")) || !array(root.get("explanation"))
                    || !array(root.get("actions"))) {
                return Optional.empty();
            }
            List<String> refs = new ArrayList<>();
            for (JsonNode item : root.get("financialData")) {
                if (!item.isObject() || !string(item.get("ref"))) {
                    return Optional.empty();
                }
                refs.add(item.get("ref").asString());
            }
            List<String> steps = new ArrayList<>();
            for (JsonNode item : root.get("explanation")) {
                if (!string(item)) {
                    return Optional.empty();
                }
                steps.add(item.asString());
            }
            List<AnswerDraft.Action> actions = new ArrayList<>();
            for (JsonNode item : root.get("actions")) {
                if (!item.isObject() || !string(item.get("kind"))) {
                    return Optional.empty();
                }
                actions.add(new AnswerDraft.Action(item.get("kind").asString(), text(item.get("target"))));
            }
            return Optional.of(new AnswerDraft(root.get("answer").asString(), root.get("type").asString(), refs,
                    steps, actions));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /**
     * Bloco com as perguntas anteriores da pessoa (ADR-0040 §7), vazio sem elas. Texto da pessoa, escapado como a
     * pergunta atual; nunca uma resposta do Copilot.
     */
    private String previous(List<String> questions) {
        return questions.isEmpty() ? "" : "<perguntas_anteriores>" + jsonText(questions) + "</perguntas_anteriores>\n";
    }

    /**
     * JSON com {@code <} e {@code >} escapados (escape unicode do JSON): nenhum texto da pessoa ou de importação
     * fecha um bloco delimitado.
     */
    private String jsonText(Object value) {
        return json.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e");
    }

    private static boolean string(JsonNode node) {
        return node != null && node.isString();
    }

    private static boolean array(JsonNode node) {
        return node != null && node.isArray();
    }

    private static String text(JsonNode node) {
        return string(node) ? node.asString() : null;
    }
}
