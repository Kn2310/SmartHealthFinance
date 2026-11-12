package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.AnswerSegment;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Conferências de um caso do golden set (ADR-0042 §2, §3). {@link #checkScripted} confere tudo o que o caso espera
 * da saída roteirizada; {@link #checkReal} confere só o que não depende do texto do modelo: intenção, código, que
 * não houve modo seguro e que todo número do texto vem de um valor do contexto. Cada falha tem um código curto
 * ({@link Failure#check()}), o único dado que o relatório da avaliação real guarda.
 */
final class GoldenChecks {

    /** {@code detail} pode trazer texto esperado e obtido: só o CI o mostra, o relatório da avaliação real não. */
    record Failure(String check, String detail) {

        @Override
        public String toString() {
            return check + ": " + detail;
        }
    }

    private static final Pattern DIGIT = Pattern.compile("\\p{N}");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private GoldenChecks() {
    }

    static List<Failure> checkScripted(GoldenCase golden, GoldenHarness.Outcome outcome, ScriptedGateway gateway) {
        List<Failure> failures = new ArrayList<>();
        GoldenCase.Expect expect = golden.expect();
        compareErrorAndResult(expect, outcome, failures);
        if (outcome.result() != null) {
            CopilotResult result = outcome.result();
            equal("CODE_MISMATCH", expect.code(), result.code(), failures);
            if (expect.intent() != null) {
                equal("INTENT_MISMATCH", expect.intent(), intentName(result), failures);
            }
            equal("VALIDATION_MISMATCH", expect.validation(), result.validation(), failures);
            equal("CONFIDENCE_MISMATCH", expect.confidence(), result.confidence(), failures);
            if (expect.notices() != null) {
                equal("NOTICES_MISMATCH", expect.notices(), result.notices().stream().map(Enum::name).toList(),
                        failures);
            }
            answerExpectations(expect, result, failures);
            invariants(golden, result, failures);
        }
        if (expect.calls() != null) {
            equal("CALLS_MISMATCH", expect.calls(),
                    outcome.ledger().stream().map(row -> row.requestType().name()).toList(), failures);
        }
        String modelInput = gateway.intentRequests.stream().map(GoldenChecks::input)
                .collect(Collectors.joining("\n")) + "\n" + gateway.answerRequests.stream().map(GoldenChecks::input)
                .collect(Collectors.joining("\n"));
        for (String text : expect.modelInputContains()) {
            if (!modelInput.contains(text)) {
                failures.add(new Failure("MODEL_INPUT_MISSING", "the model input does not contain: " + text));
            }
        }
        for (String text : expect.modelInputExcludes()) {
            if (modelInput.contains(text)) {
                failures.add(new Failure("MODEL_INPUT_LEAK", "the model input contains: " + text));
            }
        }
        if (UUID.matcher(modelInput).find()) {
            failures.add(new Failure("ID_IN_MODEL_INPUT", "an internal id reached the model"));
        }
        if (!gateway.unusedSteps().isEmpty()) {
            failures.add(new Failure("SCRIPT_UNUSED", "scripted steps the pipeline never asked for: "
                    + gateway.unusedSteps()));
        }
        return failures;
    }

    /** O que a avaliação real confere; {@code skip} é decidido por quem chama ({@link #skipped}). */
    static List<Failure> checkReal(GoldenCase golden, GoldenHarness.Outcome outcome) {
        List<Failure> failures = new ArrayList<>();
        GoldenCase.Expect expect = golden.expect();
        GoldenCase.Real real = expect.real();
        if (expect.error() != null) {
            equal("ERROR_MISMATCH", expect.error(), outcome.error(), failures);
            return failures;
        }
        if (outcome.result() == null) {
            failures.add(new Failure("ERROR_MISMATCH", "unexpected error " + outcome.error()));
            return failures;
        }
        CopilotResult result = outcome.result();
        String intent = real != null && real.intent() != null ? real.intent() : expect.intent();
        String code = real != null && real.code() != null ? real.code() : expect.code();
        if (intent != null) {
            equal("INTENT_MISMATCH", intent, intentName(result), failures);
        }
        equal("CODE_MISMATCH", code, result.code(), failures);
        if (result.validation() == ValidationStatus.SAFE_MODE) {
            failures.add(new Failure("SAFE_MODE", "the answer fell to safe mode"));
        }
        for (String text : expect.textExcludes()) {
            if (result.answerValue().map(answer -> lower(answer.text()).contains(lower(text))).orElse(false)) {
                failures.add(new Failure("TEXT_EXCLUDED", "the answer contains: " + text));
            }
        }
        boolean template = Set.of("UNSUPPORTED_ACTION", "OUT_OF_SCOPE").contains(intent);
        if (template && expect.actions() != null) {
            equal("ACTIONS_MISMATCH", expect.actions(), actionsOf(result), failures);
        }
        invariants(golden, result, failures);
        return failures;
    }

    static boolean skipped(GoldenCase golden) {
        return golden.expect().real() != null && Boolean.TRUE.equals(golden.expect().real().skip());
    }

    private static void compareErrorAndResult(GoldenCase.Expect expect, GoldenHarness.Outcome outcome,
                                              List<Failure> failures) {
        if (expect.error() != null) {
            equal("ERROR_MISMATCH", expect.error(), outcome.error(), failures);
            if (outcome.result() != null) {
                failures.add(new Failure("ERROR_MISMATCH", "an error was expected but there was a result"));
            }
        } else if (outcome.result() == null) {
            failures.add(new Failure("ERROR_MISMATCH", "unexpected error " + outcome.error()));
        }
    }

    private static void answerExpectations(GoldenCase.Expect expect, CopilotResult result, List<Failure> failures) {
        RenderedAnswer answer = result.answerValue().orElse(null);
        if (answer == null) {
            if (expect.refs() != null || !expect.citedValues().isEmpty() || !expect.textContains().isEmpty()
                    || expect.breakdown() != null || expect.actions() != null) {
                failures.add(new Failure("NO_ANSWER", "the case expects an answer but the result has none"));
            }
            return;
        }
        if (expect.refs() != null) {
            equal("REFS_MISMATCH", expect.refs(), answer.financialData().stream().map(RenderedAnswer.Value::ref)
                    .toList(), failures);
        }
        List<String> formatted = answer.financialData().stream().map(RenderedAnswer.Value::formatted).toList();
        for (String value : expect.citedValues()) {
            if (!formatted.contains(value)) {
                failures.add(new Failure("VALUE_NOT_CITED", "missing value " + value + " in " + formatted));
            }
        }
        if (expect.sources() != null) {
            equal("SOURCES_MISMATCH", new LinkedHashSet<>(expect.sources()), answer.financialData().stream()
                    .map(RenderedAnswer.Value::source).collect(Collectors.toCollection(LinkedHashSet::new)),
                    failures);
        }
        if (expect.kinds() != null) {
            equal("KINDS_MISMATCH", new LinkedHashSet<>(expect.kinds()), answer.financialData().stream()
                    .map(value -> value.kind().name()).collect(Collectors.toCollection(LinkedHashSet::new)),
                    failures);
        }
        if (expect.actions() != null) {
            equal("ACTIONS_MISMATCH", expect.actions(), actionsOf(result), failures);
        }
        if (expect.dataUsed() != null) {
            equal("DATA_USED_MISMATCH", expect.dataUsed(), answer.dataUsed().stream()
                    .map(item -> item.label() + (item.partial() ? " (parcial)" : "")).toList(), failures);
        }
        if (expect.breakdown() != null) {
            breakdown(expect.breakdown(), answer, failures);
        }
        String searchable = answer.text() + "\n" + String.join("\n", answer.explanation());
        for (String text : expect.textContains()) {
            if (!searchable.contains(text)) {
                failures.add(new Failure("TEXT_MISSING", "the answer does not contain: " + text));
            }
        }
        for (String text : expect.textExcludes()) {
            if (lower(searchable).contains(lower(text))) {
                failures.add(new Failure("TEXT_EXCLUDED", "the answer contains: " + text));
            }
        }
    }

    private static void breakdown(GoldenCase.ExpectedBreakdown expected, RenderedAnswer answer, List<Failure> failures) {
        if (answer.breakdown() == null) {
            failures.add(new Failure("BREAKDOWN_MISMATCH", "the answer has no breakdown"));
            return;
        }
        equal("BREAKDOWN_MISMATCH", expected.title(), answer.breakdown().title(), failures);
        List<GoldenCase.ExpectedBreakdown.ExpectedRow> rows = answer.breakdown().rows().stream()
                .map(row -> new GoldenCase.ExpectedBreakdown.ExpectedRow(row.label(), formattedOf(answer, row.valueRef())))
                .toList();
        equal("BREAKDOWN_MISMATCH", expected.rows(), rows, failures);
        if (expected.total() != null) {
            equal("BREAKDOWN_MISMATCH", expected.total(), formattedOf(answer, answer.breakdown().totalRef()), failures);
        }
    }

    private static String formattedOf(RenderedAnswer answer, String ref) {
        return answer.financialData().stream().filter(value -> value.ref().equals(ref)).findFirst()
                .map(RenderedAnswer.Value::formatted).orElse("<ref " + ref + " not in financialData>");
    }

    /** Valem para qualquer resposta, do roteiro ou do modelo real: o número só vem do contexto. */
    private static void invariants(GoldenCase golden, CopilotResult result, List<Failure> failures) {
        boolean answered = result.code() == ResultCode.OK || result.code() == ResultCode.SAFE_MODE;
        if (answered && (result.code() == ResultCode.SAFE_MODE) != (result.validation() == ValidationStatus.SAFE_MODE)) {
            failures.add(new Failure("CODE_VALIDATION_INCONSISTENT", result.code() + " with " + result.validation()));
        }
        if (result.answerValue().isEmpty()) {
            return;
        }
        RenderedAnswer answer = result.answerValue().get();
        List<String> names = golden.fixtures().catalog() == null ? List.of()
                : golden.fixtures().catalog().targets().stream().map(GoldenCase.Target::name).toList();
        int values = golden.fixtures().tool() == null ? 0 : golden.fixtures().tool().values().size();
        Set<String> cited = answer.financialData().stream().map(RenderedAnswer.Value::ref)
                .collect(Collectors.toSet());
        for (String ref : cited) {
            if (!ref.matches("v[1-9]\\d*") || Integer.parseInt(ref.substring(1)) > values) {
                failures.add(new Failure("REF_NOT_IN_CONTEXT", "the answer cites " + ref + " but the context has "
                        + values + " values"));
            }
        }
        Stream.concat(answer.segments().stream(), answer.explanationSegments().stream().flatMap(List::stream))
                .forEach(segment -> {
                    if (segment instanceof AnswerSegment.Value value && !cited.contains(value.ref())) {
                        failures.add(new Failure("SEGMENT_REF_NOT_CITED", value.ref() + " is in the text only"));
                    }
                    if (segment instanceof AnswerSegment.Text text && hasNumberOutsideValues(text.text(), names)) {
                        failures.add(new Failure("NUMBER_OUTSIDE_VALUES", "a digit or R$/% in a text segment"));
                    }
                });
    }

    /** Texto fora de {@code {{v:ref}}} não pode ter dígito, {@code R$} nem {@code %} (os nomes que a pessoa dá são tirados). */
    private static boolean hasNumberOutsideValues(String text, List<String> displayNames) {
        String rest = text;
        for (String name : displayNames) {
            rest = rest.replace(name, " ");
        }
        return DIGIT.matcher(rest).find() || rest.contains("R$") || rest.contains("%");
    }

    private static List<GoldenCase.ExpectedAction> actionsOf(CopilotResult result) {
        return result.answerValue().map(answer -> answer.actions().stream()
                .map(action -> new GoldenCase.ExpectedAction(action.kind().name(), action.targetName(),
                        action.params())).toList()).orElse(List.of());
    }

    private static String intentName(CopilotResult result) {
        Intent intent = result.intent();
        return intent == null ? "NONE" : intent.name();
    }

    private static String input(AiRequest request) {
        return request.systemPrompt() + "\n" + request.userContent();
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static void equal(String check, Object expected, Object actual, List<Failure> failures) {
        if (expected == null) {
            return;
        }
        Object left = expected instanceof Enum<?> e ? e.name() : expected;
        Object right = actual instanceof Enum<?> e ? e.name() : actual;
        if (!Objects.equals(left, right)) {
            failures.add(new Failure(check, "expected " + left + " but was " + right));
        }
    }
}
