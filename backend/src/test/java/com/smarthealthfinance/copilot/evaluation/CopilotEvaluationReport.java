package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Relatório da avaliação com o modelo real (ADR-0042 §3): acerto geral e por intenção, violações de claim por código,
 * modo seguro, latência p50/p95 por papel, tokens e custo (total e por caso). <b>Só ids e códigos</b>: nunca o texto da
 * pergunta, da resposta ou do contexto, nem o {@code note} do caso, nem o detalhe das falhas (que pode citar texto).
 * O Markdown é desenhado a partir do mesmo JSON, para os dois nunca divergirem.
 */
final class CopilotEvaluationReport {

    /** Falhas que significam um número que não vem do contexto chegando à pessoa. */
    static final Set<String> CLAIM_LEAKS = Set.of("NUMBER_OUTSIDE_VALUES", "REF_NOT_IN_CONTEXT",
            "SEGMENT_REF_NOT_CITED");

    /** Contexto da execução: o que foi medido, com que preços e se parou no teto de custo. */
    record Meta(String goldenVersion, String pricingVersion, String intentModel, String answerModel,
                BigDecimal maxUsd, String abortReason, int skipped, int notSelected) {
    }

    /** Um caso avaliado e o que falhou nele. */
    record Run(GoldenCase golden, GoldenHarness.Outcome outcome, List<GoldenChecks.Failure> failures) {

        boolean passed() {
            return failures.isEmpty();
        }

        String expectedIntent() {
            GoldenCase.Expect expect = golden.expect();
            if (expect.real() != null && expect.real().intent() != null) {
                return expect.real().intent();
            }
            return expect.intent() == null ? "NONE" : expect.intent();
        }

        BigDecimal cost() {
            return outcome.ledger().stream().map(AiUsage::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private CopilotEvaluationReport() {
    }

    static ObjectNode json(JsonMapper mapper, Meta meta, List<Run> runs) {
        ObjectNode root = mapper.createObjectNode();
        root.put("goldenVersion", meta.goldenVersion());
        root.put("pricingVersion", meta.pricingVersion());
        ObjectNode models = root.putObject("models");
        models.put("intent", meta.intentModel());
        models.put("answer", meta.answerModel());
        versions(root, runs);

        int evaluated = runs.size();
        long passed = runs.stream().filter(Run::passed).count();
        ObjectNode cases = root.putObject("cases");
        cases.put("evaluated", evaluated);
        cases.put("passed", passed);
        cases.put("failed", evaluated - passed);
        cases.put("skipped", meta.skipped());
        cases.put("notSelected", meta.notSelected());
        root.put("aborted", meta.abortReason() != null);
        if (meta.abortReason() != null) {
            root.put("abortReason", meta.abortReason());
        }
        root.put("accuracy", rate(passed, evaluated));

        ObjectNode byIntent = root.putObject("accuracyByIntent");
        Map<String, List<Run>> grouped = new TreeMap<>();
        runs.forEach(run -> grouped.computeIfAbsent(run.expectedIntent(), key -> new ArrayList<>()).add(run));
        grouped.forEach((intent, group) -> {
            ObjectNode node = byIntent.putObject(intent);
            long ok = group.stream().filter(Run::passed).count();
            node.put("evaluated", group.size());
            node.put("passed", ok);
            node.put("accuracy", rate(ok, group.size()));
        });

        Map<String, Long> violations = new TreeMap<>();
        runs.forEach(run -> run.outcome().violations().forEach((code, count) -> violations.merge(code, count,
                Long::sum)));
        ObjectNode violationNode = root.putObject("claimViolationsByCode");
        violations.forEach((code, count) -> violationNode.put(code, count.longValue()));
        long regenerated = runs.stream().filter(run -> validation(run) == ValidationStatus.REGENERATED).count();
        long safeMode = runs.stream().filter(run -> validation(run) == ValidationStatus.SAFE_MODE).count();
        long leaks = runs.stream().filter(run -> run.failures().stream()
                .anyMatch(failure -> CLAIM_LEAKS.contains(failure.check()))).count();
        ObjectNode quality = root.putObject("quality");
        quality.put("regenerated", regenerated);
        quality.put("regeneratedRate", rate(regenerated, evaluated));
        quality.put("safeMode", safeMode);
        quality.put("safeModeRate", rate(safeMode, evaluated));
        quality.put("claimLeaksToThePerson", leaks);

        List<AiUsage> rows = runs.stream().flatMap(run -> run.outcome().ledger().stream()).toList();
        ObjectNode latency = root.putObject("latencyMs");
        latency(latency.putObject("intent"), rows.stream().filter(row -> row.requestType() == AiRequestType.INTENT)
                .map(AiUsage::latencyMs).toList());
        latency(latency.putObject("answer"), rows.stream().filter(row -> row.requestType() != AiRequestType.INTENT)
                .map(AiUsage::latencyMs).toList());

        ObjectNode tokens = root.putObject("tokens");
        tokens(tokens.putObject("intent"), rows.stream().filter(row -> row.requestType() == AiRequestType.INTENT)
                .toList());
        tokens(tokens.putObject("answer"), rows.stream().filter(row -> row.requestType() != AiRequestType.INTENT)
                .toList());
        tokens(tokens.putObject("total"), rows);

        BigDecimal total = runs.stream().map(Run::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        ObjectNode cost = root.putObject("costUsd");
        cost.put("total", total);
        cost.put("ceiling", meta.maxUsd());
        ObjectNode byCase = cost.putObject("byCase");
        runs.forEach(run -> byCase.put(run.golden().id(), run.cost()));

        ArrayNode list = root.putArray("results");
        for (Run run : runs) {
            ObjectNode node = list.addObject();
            node.put("id", run.golden().id());
            node.put("group", run.golden().group());
            node.put("expectedIntent", run.expectedIntent());
            node.put("intent", run.outcome().result() == null || run.outcome().result().intent() == null ? "NONE"
                    : run.outcome().result().intent().name());
            node.put("code", run.outcome().result() == null ? run.outcome().error() == null ? "NONE" : "ERROR"
                    : run.outcome().result().code().name());
            node.put("validation", validation(run).name());
            node.put("passed", run.passed());
            ArrayNode failures = node.putArray("failures");
            run.failures().stream().map(GoldenChecks.Failure::check).distinct().forEach(failures::add);
            node.put("modelCalls", run.outcome().ledger().size());
            node.put("costUsd", run.cost());
        }
        return root;
    }

    private static ValidationStatus validation(Run run) {
        return run.outcome().result() == null ? ValidationStatus.NOT_APPLICABLE : run.outcome().result().validation();
    }

    private static void versions(ObjectNode root, List<Run> runs) {
        runs.stream().map(run -> run.outcome().result()).filter(result -> result != null
                && !result.versions().isEmpty()).findFirst().ifPresent(result -> {
            ObjectNode node = root.putObject("versions");
            new TreeMap<>(result.versions()).forEach(node::put);
        });
    }

    private static void latency(ObjectNode node, List<Long> values) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        node.put("calls", sorted.size());
        node.put("p50", percentile(sorted, 50));
        node.put("p95", percentile(sorted, 95));
        node.put("max", sorted.isEmpty() ? 0 : sorted.getLast());
    }

    /** Nearest-rank: o menor valor com pelo menos {@code p}% das amostras abaixo ou iguais. */
    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void tokens(ObjectNode node, List<AiUsage> rows) {
        node.put("input", rows.stream().mapToLong(row -> row.tokens().input()).sum());
        node.put("output", rows.stream().mapToLong(row -> row.tokens().output()).sum());
        node.put("cacheRead", rows.stream().mapToLong(row -> row.tokens().cacheRead()).sum());
        node.put("cacheWrite", rows.stream().mapToLong(row -> row.tokens().cacheWrite()).sum());
    }

    private static BigDecimal rate(long part, long whole) {
        return whole == 0 ? BigDecimal.ZERO.setScale(4)
                : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_EVEN);
    }

    static String markdown(JsonNode report) {
        StringBuilder md = new StringBuilder("# Avaliação do Copilot com o modelo real\n\n");
        md.append("Golden set `").append(report.get("goldenVersion").asString()).append("` · preços `")
                .append(report.get("pricingVersion").asString()).append("` · intenção `")
                .append(report.get("models").get("intent").asString()).append("` · resposta `")
                .append(report.get("models").get("answer").asString()).append("`\n\n");
        if (report.has("versions")) {
            Map<String, String> versions = new LinkedHashMap<>();
            report.get("versions").properties().forEach(entry -> versions.put(entry.getKey(),
                    entry.getValue().asString()));
            md.append("Versões: ").append(versions).append("\n\n");
        }
        if (report.get("aborted").asBoolean()) {
            md.append("> **Execução interrompida"
                    + " (motivo: `" + report.get("abortReason").asString() + "`).** Os números abaixo cobrem só os casos já "
                    + "avaliados.\n\n");
        }
        JsonNode cases = report.get("cases");
        md.append("## Resumo\n\n| Medida | Valor |\n|---|---|\n");
        md.append("| Casos avaliados | ").append(cases.get("evaluated").asInt()).append(" (puladas: ")
                .append(cases.get("skipped").asInt()).append(", fora da seleção: ")
                .append(cases.get("notSelected").asInt()).append(") |\n");
        md.append("| Acerto geral | ").append(percent(report.get("accuracy"))).append(" (")
                .append(cases.get("passed").asInt()).append(" de ").append(cases.get("evaluated").asInt())
                .append(") |\n");
        JsonNode quality = report.get("quality");
        md.append("| Modo seguro | ").append(percent(quality.get("safeModeRate"))).append(" (")
                .append(quality.get("safeMode").asInt()).append(") |\n");
        md.append("| Regenerada | ").append(percent(quality.get("regeneratedRate"))).append(" (")
                .append(quality.get("regenerated").asInt()).append(") |\n");
        md.append("| Número fora do contexto que chegou à pessoa | ")
                .append(quality.get("claimLeaksToThePerson").asInt()).append(" |\n");
        md.append("| Custo total | US$ ").append(report.get("costUsd").get("total").decimalValue().toPlainString())
                .append(" (teto US$ ").append(report.get("costUsd").get("ceiling").decimalValue().toPlainString())
                .append(") |\n\n");

        md.append("## Acerto por intenção\n\n| Intenção | Casos | Acerto |\n|---|---|---|\n");
        report.get("accuracyByIntent").properties().forEach(entry -> md.append("| ").append(entry.getKey())
                .append(" | ").append(entry.getValue().get("evaluated").asInt()).append(" | ")
                .append(percent(entry.getValue().get("accuracy"))).append(" |\n"));

        md.append("\n## Violações de claim por código\n\n");
        if (report.get("claimViolationsByCode").size() == 0) {
            md.append("Nenhuma.\n");
        } else {
            md.append("| Código | Ocorrências |\n|---|---|\n");
            report.get("claimViolationsByCode").properties().forEach(entry -> md.append("| ").append(entry.getKey())
                    .append(" | ").append(entry.getValue().asLong()).append(" |\n"));
        }

        md.append("\n## Latência (ms)\n\n| Papel | Chamadas | p50 | p95 | máx |\n|---|---|---|---|---|\n");
        for (String role : List.of("intent", "answer")) {
            JsonNode latency = report.get("latencyMs").get(role);
            md.append("| ").append(role).append(" | ").append(latency.get("calls").asInt()).append(" | ")
                    .append(latency.get("p50").asLong()).append(" | ").append(latency.get("p95").asLong())
                    .append(" | ").append(latency.get("max").asLong()).append(" |\n");
        }

        md.append("\n## Tokens\n\n| Papel | Entrada | Saída | Cache lido | Cache escrito |\n|---|---|---|---|---|\n");
        for (String role : List.of("intent", "answer", "total")) {
            JsonNode tokens = report.get("tokens").get(role);
            md.append("| ").append(role).append(" | ").append(tokens.get("input").asLong()).append(" | ")
                    .append(tokens.get("output").asLong()).append(" | ").append(tokens.get("cacheRead").asLong())
                    .append(" | ").append(tokens.get("cacheWrite").asLong()).append(" |\n");
        }

        md.append("\n## Casos que falharam\n\n");
        List<String> failed = new ArrayList<>();
        for (JsonNode result : report.get("results")) {
            if (!result.get("passed").asBoolean()) {
                StringBuilder codes = new StringBuilder();
                result.get("failures").forEach(code -> codes.append(codes.isEmpty() ? "" : ", ")
                        .append(code.asString()));
                failed.add("| " + result.get("id").asString() + " | " + result.get("group").asString() + " | "
                        + result.get("expectedIntent").asString() + " → " + result.get("intent").asString() + " | "
                        + result.get("code").asString() + " | " + codes + " |");
            }
        }
        if (failed.isEmpty()) {
            md.append("Nenhum.\n");
        } else {
            md.append("| Caso | Grupo | Intenção esperada → obtida | Código | Falhas |\n|---|---|---|---|---|\n");
            failed.forEach(line -> md.append(line).append('\n'));
        }
        md.append("\nCusto por caso e resultado de cada caso: `report.json`. O relatório não guarda texto de "
                + "pergunta, resposta ou contexto.\n");
        return md.toString();
    }

    private static String percent(JsonNode rate) {
        return rate.decimalValue().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_EVEN)
                .toPlainString() + "%";
    }
}
