package com.smarthealthfinance.copilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Painel e alertas do Copilot (ADR-0042 §4) são válidos e só citam métricas que o código emite, para nenhum alerta
 * ficar mudo depois de um renome. Nomes no formato do Prometheus: pontos viram "_", contadores ganham "_total" e
 * timers ganham "_seconds_*".
 */
class CopilotObservabilityAssetsTest {

    private static final Path ASSETS = Path.of("..", "infrastructure", "observability", "copilot");
    private static final Path SOURCES = Path.of("src", "main", "java", "com", "smarthealthfinance", "copilot");
    private static final Pattern EMITTED = Pattern.compile("\"(shf\\.copilot\\.[a-z_.]+)\"");
    private static final Pattern REFERENCED = Pattern.compile("\\b(shf_copilot_[a-z_]+)");
    private static final List<String> SUFFIXES = List.of("_total", "_seconds_bucket", "_seconds_count",
            "_seconds_sum", "_seconds_max", "_seconds", "");

    @Test
    void alertRulesAreValidAndActionable() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(ASSETS.resolve("alerts.yml")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertThat(groups).isNotEmpty();
        for (Map<String, Object> group : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
            assertThat(rules).isNotEmpty();
            for (Map<String, Object> rule : rules) {
                assertThat(rule).containsKeys("alert", "expr", "labels", "annotations");
                @SuppressWarnings("unchecked")
                Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
                assertThat(labels.get("severity")).as("%s severity", rule.get("alert"))
                        .isIn("P1", "P2", "P3", "P4");
                @SuppressWarnings("unchecked")
                Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
                assertThat(annotations).as("%s annotations", rule.get("alert")).containsKeys("summary", "runbook");
            }
        }
    }

    @Test
    void dashboardIsValidJsonWithQueries() throws IOException {
        JsonNode dashboard = JsonMapper.builder().build().readTree(Files.readString(ASSETS.resolve("dashboard.json")));
        assertThat(dashboard.get("uid").asString()).isEqualTo("shf-copilot");
        assertThat(dashboard.get("panels").size()).isGreaterThan(0);
        dashboard.get("panels").forEach(panel ->
                assertThat(panel.get("targets").size()).as(panel.get("title").asString()).isGreaterThan(0));
    }

    @Test
    void assetsOnlyReferenceMetricsTheCodeEmits() throws IOException {
        Set<String> emitted = emittedPrometheusNames();
        assertThat(emitted).isNotEmpty();
        Set<String> missing = new TreeSet<>();
        for (String file : List.of("alerts.yml", "dashboard.json")) {
            Matcher matcher = REFERENCED.matcher(Files.readString(ASSETS.resolve(file)));
            while (matcher.find()) {
                String name = matcher.group(1);
                boolean known = SUFFIXES.stream().anyMatch(suffix -> name.endsWith(suffix)
                        && emitted.contains(name.substring(0, name.length() - suffix.length())));
                if (!known) {
                    missing.add(file + ": " + name);
                }
            }
        }
        assertThat(missing).as("métricas citadas que o código não emite").isEmpty();
    }

    @Test
    void assetsNeverUseHighCardinalityOrPersonalLabels() throws IOException {
        // Nem como seletor nem como agrupamento: os comentários podem citar o nome para dizer que ele não existe.
        Pattern personal = Pattern.compile("(?i)(workspace|user)_?id\\s*(=~|!~|!=|=)|by\\s*\\([^)]*(workspace|user)_?id");
        for (String file : List.of("alerts.yml", "dashboard.json")) {
            assertThat(personal.matcher(Files.readString(ASSETS.resolve(file))).find()).as(file).isFalse();
        }
    }

    private static Set<String> emittedPrometheusNames() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = EMITTED.matcher(Files.readString(file));
                while (matcher.find()) {
                    names.add(matcher.group(1).replace('.', '_'));
                }
            }
        }
        return names;
    }
}
