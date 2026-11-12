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
    private static final Pattern TIMER = Pattern.compile("(?:Timer\\.builder|\\.timer)\\(\\s*\"(shf\\.copilot\\.[a-z_.]+)\"");
    private static final Pattern COUNTER = Pattern.compile("(?:Counter\\.builder|\\.counter)\\(\\s*\"(shf\\.copilot\\.[a-z_.]+)\"");
    private static final List<String> TIMER_SUFFIXES = List.of("_seconds_bucket", "_seconds_count", "_seconds_sum",
            "_seconds_max");
    private static final Path APPLICATION_YML = Path.of("src", "main", "resources", "application.yml");
    private static final Path RUNBOOK = Path.of("..", "docs", "runbooks", "copilot.md");
    private static final Path CONFIGURATION = Path.of("src", "main", "java", "com", "smarthealthfinance", "copilot",
            "infrastructure", "config", "CopilotConfiguration.java");

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
        Set<String> timers = names(TIMER);
        Set<String> counters = names(COUNTER);
        Set<String> others = names(EMITTED);
        others.removeAll(timers);
        others.removeAll(counters);
        assertThat(timers).isNotEmpty();
        assertThat(counters).isNotEmpty();
        Set<String> missing = new TreeSet<>();
        for (String file : List.of("alerts.yml", "dashboard.json")) {
            Matcher matcher = REFERENCED.matcher(Files.readString(ASSETS.resolve(file)));
            while (matcher.find()) {
                String name = matcher.group(1);
                // Sufixo por tipo: contador só com "_total", timer só com "_seconds_*", o resto exato.
                boolean known = (name.endsWith("_total")
                        && counters.contains(name.substring(0, name.length() - "_total".length())))
                        || TIMER_SUFFIXES.stream().anyMatch(suffix -> name.endsWith(suffix)
                        && timers.contains(name.substring(0, name.length() - suffix.length())))
                        || others.contains(name);
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

    @Test
    void theTwoHistogramTimersHaveBucketsEnabled() throws IOException {
        Set<String> withBuckets = new TreeSet<>();
        for (String file : List.of("alerts.yml", "dashboard.json")) {
            Matcher matcher = Pattern.compile("\\b(shf_copilot_[a-z_]+)_seconds_bucket")
                    .matcher(Files.readString(ASSETS.resolve(file)));
            while (matcher.find()) {
                withBuckets.add(matcher.group(1));
            }
        }
        assertThat(withBuckets).as("timers citados com _bucket").isNotEmpty();
        // Os nomes são lidos do texto do application.yml: "[shf.copilot.x]": true sob percentiles-histogram.
        String yml = Files.readString(APPLICATION_YML);
        for (String timer : withBuckets) {
            // O nome do Prometheus perde a separação por pontos; reconstrói a partir dos timers emitidos.
            assertThat(names(TIMER)).as("Timer emitido").contains(timer);
            String real = timer;
            assertThat(yml).as("percentiles-histogram de %s", real)
                    .containsPattern("\"\\[" + Pattern.quote(real.replace('_', '.')) + "\\]\"\\s*:\\s*true");
        }
    }

    @Test
    void runbookAnchorsPointToExistingHeadings() throws IOException {
        Set<String> slugs = new TreeSet<>();
        for (String line : Files.readAllLines(RUNBOOK)) {
            if (line.matches("#{1,6}\\s+.+")) {
                slugs.add(slug(line.replaceFirst("#{1,6}\\s+", "")));
            }
        }
        int checked = 0;
        for (String runbook : Pattern.compile("runbook:\\s*\"([^\"]+)\"").matcher(Files.readString(
                ASSETS.resolve("alerts.yml"))).results().map(result -> result.group(1)).toList()) {
            assertThat(runbook).startsWith("docs/runbooks/copilot.md");
            int hash = runbook.indexOf('#');
            if (hash >= 0) {
                assertThat(slugs).as(runbook).contains(runbook.substring(hash + 1));
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(0);
    }

    @Test
    void circuitBreakerAlertUsesTheRealResilienceName() throws IOException {
        Matcher matcher = Pattern.compile("RESILIENCE_NAME\\s*=\\s*\"([^\"]+)\"")
                .matcher(Files.readString(CONFIGURATION));
        assertThat(matcher.find()).isTrue();
        String name = matcher.group(1);
        for (String file : List.of("alerts.yml", "dashboard.json")) {
            String content = Files.readString(ASSETS.resolve(file));
            assertThat(content).as(file).contains("resilience4j_circuitbreaker_state");
            Matcher names = Pattern.compile("resilience4j_circuitbreaker_state\\{name=\\\\?\"([^\"\\\\]+)").matcher(content);
            while (names.find()) {
                assertThat(names.group(1)).as(file).isEqualTo(name);
            }
        }
    }

    /** Slug no estilo do GitHub: minúsculas, espaços viram "-", pontuação some (menos "-"), acentos ficam. */
    private static String slug(String heading) {
        return heading.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s_-]", "")
                .replace(' ', '-');
    }

    private static Set<String> names(Pattern pattern) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    names.add(matcher.group(1).replace('.', '_'));
                }
            }
        }
        return names;
    }
}
