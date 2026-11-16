package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.domain.enums.Intent;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Um caso do golden set {@code golden-v1} (ADR-0042 §1), como está nos JSON de {@code copilot/golden}. O formato é
 * documentado em {@code copilot/golden/README.md} e validado por {@code golden-case.schema.json}. Todos os dados são
 * sintéticos. Campos ausentes viram listas vazias ou nulos.
 */
public record GoldenCase(String id, String group, String note, String question, List<Previous> previousQuestions,
                         EntryRef context, Fixtures fixtures, Scripted scripted, Expect expect) {

    public GoldenCase {
        previousQuestions = previousQuestions == null ? List.of() : List.copyOf(previousQuestions);
        fixtures = fixtures == null ? new Fixtures(null, null, null) : fixtures;
    }

    /** Pergunta anterior da mesma conversa, com a intenção gravada (ADR-0040 §7). */
    public record Previous(String question, Intent intent) {
    }

    /** Contexto de entrada como na API: {@code INSIGHT}, {@code INVOICE}, {@code INSTALLMENT_PLAN} ou {@code GOAL}. */
    public record EntryRef(String kind, String insightId, String cardId, String month, String planId, String goalId) {
    }

    /** O que o Workspace sintético tem: catálogo, resolução do contexto de entrada e o que as tools devolvem. */
    public record Fixtures(Catalog catalog, Entry entry, Tool tool) {
    }

    /** Catálogo da pergunta (RequestCatalog): categorias do sistema e alvos pseudonimizados por tipo e na ordem. */
    public record Catalog(String currency, Map<String, String> categories, List<Target> targets) {

        public Catalog {
            categories = categories == null ? Map.of() : Map.copyOf(categories);
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /** Alvo do catálogo; o pseudônimo é {@code <prefixo do tipo>_<ordem no tipo>} ({@code cartao_1}, {@code objetivo_2}). */
    public record Target(String kind, String id, String name, String descriptor, String description) {
    }

    /** Resolução do contexto de entrada; {@code verifyFails} = alvo de outro Workspace; {@code resolveFails} = sumiu. */
    public record Entry(Map<String, String> targets, String month, Screen screen, Boolean verifyFails,
                        Boolean resolveFails, List<Target> assigns) {

        public Entry {
            targets = targets == null ? Map.of() : Map.copyOf(targets);
            assigns = assigns == null ? List.of() : List.copyOf(assigns);
        }
    }

    /** Link da tela do contexto. */
    public record Screen(String kind, String target, Map<String, String> params) {
    }

    /** O que a tool de qualquer intenção com dados devolve (valores na ordem de prioridade: v1, v2, ...). */
    public record Tool(String status, String failure, String source, List<Value> values, Quality quality,
                       List<Source> sources, List<Offer> actions, Breakdown breakdown) {

        public Tool {
            values = values == null ? List.of() : List.copyOf(values);
            sources = sources == null ? List.of() : List.copyOf(sources);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record Value(String label, String value, String unit, String kind, String period, String source) {
    }

    public record Quality(Integer monthsWithData, Boolean partialPeriod, List<String> gaps, String freshness,
                          List<String> sources) {
    }

    public record Source(String tool, String version) {
    }

    public record Offer(String kind, String target) {
    }

    public record Breakdown(String title, List<Row> rows, Integer totalIndex) {

        public record Row(String label, int valueIndex, String detail) {
        }
    }

    /**
     * Saída roteirizada do CI. {@code intent} e cada item de {@code answers}: o JSON do esquema, ou
     * {@code {"outcome":"UNAVAILABLE"}} (falha do provedor), ou {@code {"raw":"texto"}} (saída que não é JSON).
     * Ignorada na avaliação com o modelo real.
     */
    public record Scripted(JsonNode intent, List<JsonNode> answers) {

        public Scripted {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }

    /**
     * O esperado. Tudo é opcional e só é conferido quando presente. Os campos de resposta valem para o CI (saída
     * roteirizada); na avaliação real vale só {@code real} (e as invariantes gerais).
     */
    public record Expect(String code, String intent, String validation, List<String> calls, String error,
                         String confidence, List<String> refs, List<String> citedValues, List<String> sources,
                         List<String> kinds, List<String> notices, List<ExpectedAction> actions,
                         ExpectedBreakdown breakdown, List<String> dataUsed, List<String> textContains,
                         List<String> textExcludes, List<String> modelInputContains,
                         List<String> modelInputExcludes, Real real) {

        public Expect {
            calls = calls == null ? null : List.copyOf(calls);
            refs = refs == null ? null : List.copyOf(refs);
            citedValues = citedValues == null ? List.of() : List.copyOf(citedValues);
            sources = sources == null ? null : List.copyOf(sources);
            kinds = kinds == null ? null : List.copyOf(kinds);
            notices = notices == null ? null : List.copyOf(notices);
            actions = actions == null ? null : List.copyOf(actions);
            dataUsed = dataUsed == null ? null : List.copyOf(dataUsed);
            textContains = textContains == null ? List.of() : List.copyOf(textContains);
            textExcludes = textExcludes == null ? List.of() : List.copyOf(textExcludes);
            modelInputContains = modelInputContains == null ? List.of() : List.copyOf(modelInputContains);
            modelInputExcludes = modelInputExcludes == null ? List.of() : List.copyOf(modelInputExcludes);
        }
    }

    /** Ação esperada na resposta: tipo, nome mostrado do alvo (nulo para telas do Workspace) e parâmetros. */
    public record ExpectedAction(String kind, String targetName, Map<String, String> params) {

        public ExpectedAction {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** Quadro esperado: título, cada linha (rótulo e valor formatado) e o total formatado. */
    public record ExpectedBreakdown(String title, List<ExpectedRow> rows, String total) {

        public record ExpectedRow(String label, String formatted) {
        }
    }

    /**
     * Expectativa na avaliação com o modelo real: {@code skip} para casos que dependem de falha roteirizada do
     * provedor; {@code code} e {@code intent} substituem os de {@code expect} (ex.: a pergunta que o modelo
     * roteirizado errava e o real deve responder com {@code OK}).
     */
    public record Real(Boolean skip, String code, String intent) {
    }
}
