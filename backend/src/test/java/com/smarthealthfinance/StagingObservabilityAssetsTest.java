package com.smarthealthfinance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Observabilidade do staging (ADR-0044 §7, itens 31-33): as regras do Grafana são a conversão fiel do alerts.yml do
 * Copilot (a fonte), as regras e o painel de saúde só citam métricas que existem, e o contact point do Telegram nunca
 * leva segredo literal.
 */
class StagingObservabilityAssetsTest {

    private static final Path COPILOT = Path.of("..", "infrastructure", "observability", "copilot");
    private static final Path STAGING = Path.of("..", "infrastructure", "staging", "observability");
    private static final Path GRAFANA = STAGING.resolve("grafana");
    private static final Path RULES = GRAFANA.resolve("provisioning/alerting/shf-alert-rules.yaml");
    private static final Path CONTACT_POINTS = GRAFANA.resolve("provisioning/alerting/shf-contact-points.yaml");
    private static final Path POLICIES = GRAFANA.resolve("provisioning/alerting/shf-notification-policies.yaml");
    private static final Path HEALTH_DASHBOARD = GRAFANA.resolve("dashboards/shf-health.json");
    private static final Path COPILOT_DASHBOARD = GRAFANA.resolve("dashboards/shf-copilot.json");
    private static final Path SOURCES = Path.of("src", "main", "java", "com", "smarthealthfinance");

    private static final Pattern GAUGE = Pattern.compile("Gauge\\.builder\\(\\s*\"(shf\\.[a-z_.]+)\"");
    private static final Pattern METRIC_NAME = Pattern.compile("\\b((?:shf|http_server|jvm|process|rabbitmq)_[a-z0-9_]+|up)\\b(?=[{\\[(\\s)]|$)");

    /**
     * Métricas padrão do Spring Boot/Micrometer (não emitidas por código do projeto) que as regras e o painel de saúde
     * podem citar, mais "up" do próprio scrape do Prometheus e a profundidade de fila do plugin rabbitmq_prometheus.
     */
    private static final Set<String> ALLOWED_STANDARD = Set.of(
            "up",
            "http_server_requests_seconds_count", "http_server_requests_seconds_sum",
            "http_server_requests_seconds_max", "http_server_requests_seconds_bucket",
            "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "jvm_memory_max_bytes",
            "jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_sum",
            "process_cpu_usage",
            "rabbitmq_detailed_queue_messages", "rabbitmq_detailed_queue_messages_ready");

    @Test
    void grafanaCopilotRulesAreTheFaithfulConversionOfAlertsYml() throws IOException {
        Map<String, Map<String, String>> source = new LinkedHashMap<>();
        for (Map<String, Object> rule : prometheusRules()) {
            Map<String, Object> labels = map(rule.get("labels"));
            Map<String, Object> annotations = map(rule.get("annotations"));
            source.put((String) rule.get("alert"), Map.of(
                    "expr", normalize((String) rule.get("expr")),
                    "for", String.valueOf(rule.getOrDefault("for", "0s")),
                    "severity", (String) labels.get("severity"),
                    "summary", (String) annotations.get("summary"),
                    "runbook", (String) annotations.get("runbook")));
        }
        Map<String, Map<String, String>> converted = new LinkedHashMap<>();
        for (Map<String, Object> rule : grafanaRules("shf-copilot")) {
            converted.put((String) rule.get("title"), grafanaView(rule));
        }
        assertThat(source).isNotEmpty();
        assertThat(converted.keySet()).as("alertas do grupo shf-copilot").containsExactlyInAnyOrderElementsOf(
                source.keySet());
        source.forEach((name, expected) -> assertThat(converted.get(name)).as(name).isEqualTo(expected));
    }

    @Test
    void everyGrafanaRuleIsWellFormed() throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> group : groups()) {
            assertThat(group.get("folder")).isNotNull();
            all.addAll(rules(group));
        }
        assertThat(all).isNotEmpty();
        Set<Object> uids = new TreeSet<>();
        for (Map<String, Object> rule : all) {
            String title = (String) rule.get("title");
            assertThat(uids.add(rule.get("uid"))).as("uid único de %s", title).isTrue();
            assertThat(rule.get("condition")).as(title).isEqualTo("C");
            assertThat(map(rule.get("labels")).get("severity")).as(title).isIn("P1", "P2", "P3", "P4");
            assertThat(map(rule.get("annotations"))).as(title).containsKeys("summary", "runbook");
            List<Map<String, Object>> data = data(rule);
            assertThat(data).extracting(item -> item.get("refId")).as(title).containsExactly("A", "B", "C");
            assertThat(data.get(0).get("datasourceUid")).as(title).isEqualTo("prometheus");
            assertThat(map(data.get(0).get("model"))).as(title).containsEntry("instant", true);
        }
    }

    @Test
    void healthRulesAndDashboardOnlyReferenceMetricsThatExist() throws IOException {
        Set<String> emitted = emittedGauges();
        assertThat(emitted).contains("shf_outbox_pending", "shf_outbox_oldest_pending_age_seconds",
                "shf_health_snapshot_freshness_seconds", "shf_forecast_snapshot_freshness_seconds",
                "shf_insights_generation_freshness_seconds");
        Set<String> known = new TreeSet<>(emitted);
        known.addAll(ALLOWED_STANDARD);

        Set<String> cited = new TreeSet<>();
        for (Map<String, Object> rule : grafanaRules("shf-health")) {
            cited.addAll(metricsIn(expr(rule)));
        }
        assertThat(cited).as("regras de saúde citam métricas").isNotEmpty();
        JsonNode dashboard = json(HEALTH_DASHBOARD);
        assertThat(dashboard.get("uid").asString()).isEqualTo("shf-health");
        dashboard.get("panels").forEach(panel -> {
            assertThat(panel.get("targets").size()).as(panel.get("title").asString()).isGreaterThan(0);
            panel.get("targets").forEach(target -> cited.addAll(metricsIn(target.get("expr").asString())));
        });
        assertThat(cited).as("métricas citadas que não existem").isSubsetOf(known);
    }

    @Test
    void stagingCopilotDashboardIsTheOriginalWithDatasourceResolved() throws IOException {
        JsonNode original = json(COPILOT.resolve("dashboard.json"));
        String resolved = original.toString().replace("${DS_PROMETHEUS}", "prometheus");
        tools.jackson.databind.node.ObjectNode expected = (tools.jackson.databind.node.ObjectNode)
                JsonMapper.builder().build().readTree(resolved);
        expected.remove("__inputs");
        assertThat(json(COPILOT_DASHBOARD)).isEqualTo(expected);
    }

    @Test
    void contactPointNeverCarriesALiteralSecretAndMessageHasNoArbitraryLabels() throws IOException {
        String text = Files.readString(CONTACT_POINTS);
        Map<String, Object> root = new Yaml().load(text);
        List<Map<String, Object>> points = list(root.get("contactPoints"));
        assertThat(points).hasSize(1);
        Map<String, Object> receiver = map(list(points.get(0).get("receivers")).get(0));
        assertThat(receiver.get("type")).isEqualTo("telegram");
        Map<String, Object> settings = map(receiver.get("settings"));
        assertThat(settings.get("bottoken")).isEqualTo("$TELEGRAM_BOT_TOKEN");
        assertThat(settings.get("chatid")).isEqualTo("$TELEGRAM_CHAT_ID");
        // Qualquer outro valor com cara de token do Telegram (123456:ABC...) falha.
        assertThat(text).doesNotContainPattern("\\d{6,}:[A-Za-z0-9_-]{20,}");
        // Só nome, prioridade, resumo e runbook: nada de .Labels além de alertname/severity, nem .Values/.ValueString.
        String message = (String) settings.get("message");
        Matcher labels = Pattern.compile("\\.Labels\\.(\\w+)").matcher(message);
        while (labels.find()) {
            assertThat(labels.group(1)).isIn("alertname", "severity");
        }
        assertThat(message).doesNotContain(".Values", ".ValueString", ".SilenceURL", "CommonLabels", "GroupLabels");
        assertThat(message).contains(".Annotations.summary", ".Annotations.runbook");
    }

    @Test
    void notificationPolicyRoutesEverythingToTheTelegramContactPoint() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(POLICIES));
        Map<String, Object> policy = map(list(root.get("policies")).get(0));
        assertThat(policy.get("receiver")).isEqualTo("shf-telegram");
        assertThat(policy).doesNotContainKey("routes");
        Map<String, Object> contact = new Yaml().load(Files.readString(CONTACT_POINTS));
        assertThat(map(list(contact.get("contactPoints")).get(0)).get("name")).isEqualTo("shf-telegram");
    }

    @Test
    void prometheusConfigScrapesTheApiActuatorEvery15Seconds() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(STAGING.resolve("prometheus.yaml")));
        Map<String, Object> job = map(list(root.get("scrape_configs")).get(0));
        assertThat(job).containsEntry("job_name", "shf-api").containsEntry("metrics_path", "/actuator/prometheus")
                .containsEntry("scrape_interval", "15s");
        assertThat(map(list(job.get("static_configs")).get(0)).get("targets")).isEqualTo(List.of("api:8081"));
        assertThat(root).containsKeys("global", "otlp", "storage");
    }

    private static Set<String> metricsIn(String promql) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = METRIC_NAME.matcher(promql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Gauges do código no formato do Prometheus: pontos viram "_" e a unidade base "seconds" vira sufixo. */
    private static Set<String> emittedGauges() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                Matcher matcher = GAUGE.matcher(content);
                while (matcher.find()) {
                    String name = matcher.group(1).replace('.', '_');
                    int end = content.indexOf(".register(", matcher.end());
                    String chain = end < 0 ? "" : content.substring(matcher.end(), end);
                    names.add(chain.contains(".baseUnit(\"seconds\")") ? name + "_seconds" : name);
                }
            }
        }
        return names;
    }

    private static Map<String, String> grafanaView(Map<String, Object> rule) {
        Map<String, Object> annotations = map(rule.get("annotations"));
        return Map.of(
                "expr", normalize(expr(rule)),
                "for", String.valueOf(rule.get("for")),
                "severity", (String) map(rule.get("labels")).get("severity"),
                "summary", (String) annotations.get("summary"),
                "runbook", (String) annotations.get("runbook"));
    }

    private static String expr(Map<String, Object> rule) {
        return (String) map(data(rule).get(0).get("model")).get("expr");
    }

    private static List<Map<String, Object>> data(Map<String, Object> rule) {
        return list(rule.get("data"));
    }

    private static String normalize(String expr) {
        return expr.trim().replaceAll("\\s+", " ");
    }

    private static List<Map<String, Object>> prometheusRules() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(COPILOT.resolve("alerts.yml")));
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> group : list(root.get("groups"))) {
            rules.addAll(rules(group));
        }
        return rules;
    }

    private static List<Map<String, Object>> groups() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(RULES));
        assertThat(root.get("apiVersion")).isEqualTo(1);
        return list(root.get("groups"));
    }

    private static List<Map<String, Object>> grafanaRules(String group) throws IOException {
        return groups().stream().filter(candidate -> group.equals(candidate.get("name"))).findFirst()
                .map(StagingObservabilityAssetsTest::rules).orElseThrow();
    }

    private static List<Map<String, Object>> rules(Map<String, Object> group) {
        return list(group.get("rules"));
    }

    private static JsonNode json(Path path) throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
