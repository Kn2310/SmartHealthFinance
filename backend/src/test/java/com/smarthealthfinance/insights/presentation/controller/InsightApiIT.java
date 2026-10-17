package com.smarthealthfinance.insights.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.insights.domain.valueobject.EvaluationPeriod;
import com.smarthealthfinance.insights.application.service.InsightInputLoader;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * API de Insights (ADR-0023) sobre a geração real (ADR-0022): lista, filtros, contagens, paginação, detalhe,
 * destaques, relacionados, ações, estados, validação e Workspace/insight alheio.
 * <p>
 * Mesmo histórico do {@code InsightsWorkerIT}, relativo a hoje: nos 5 meses antes de P, salário de 5.000 e
 * "Restaurante Sabor" de 300 no dia 1; em P, o salário, 900 no restaurante e uma compra sem categoria de 600.
 * Dispara D1 (alimentação fora), D2 (total), D4 (a compra de 900 é 3× o valor médio) e D10 (sem categoria).
 */
class InsightApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String INSIGHTS = "/api/v1/workspaces/{workspaceId}/insights";
    private static final String INSIGHT = INSIGHTS + "/{insightId}";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InsightInputLoader loader;

    private String workspace;
    private String checking;
    private String restaurant;
    private EvaluationPeriod period;
    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        period = EvaluationPeriod.of(loader.today());
    }

    @Test
    void listWithFiltersCountsAndPages() throws Exception {
        seed();
        MvcTestResult all = list(Map.of());
        assertThat(read(all, "$.state")).isEqualTo("READY");
        assertThat(read(all, "$.algorithmVersion")).isEqualTo("insights-v1");
        assertThat(read(all, "$.period.month")).isEqualTo(period.key());
        assertThat(read(all, "$.freshness.state")).isEqualTo("UP_TO_DATE");
        int total = ((Number) read(all, "$.totalItems")).intValue();
        assertThat(total).isGreaterThanOrEqualTo(4).isEqualTo(((Number) read(all, "$.counts.all")).intValue());
        assertThat(read(all, "$.counts.cards.count")).isEqualTo(0);
        assertThat(read(all, "$.counts.cards.available")).isEqualTo(false);
        // Importantes primeiro (ADR-0021 §3).
        List<String> importance = JsonPath.read(body(all), "$.items[*].importance");
        assertThat(importance).isSortedAccordingTo((a, b) -> Boolean.compare(!a.equals("IMPORTANT"),
                !b.equals("IMPORTANT")));
        String spike = id(all, "CATEGORY_SPIKE");
        assertThat(read(all, "$.items[?(@.id == '" + spike + "')].subjectLabel")).isEqualTo(List.of("Alimentação fora"));
        assertThat(read(all, "$.items[?(@.id == '" + spike + "')].primaryAction.kind"))
                .isEqualTo(List.of("VIEW_TRANSACTIONS"));
        assertThat(read(all, "$.items[?(@.id == '" + spike + "')].seen")).isEqualTo(List.of(false));

        assertThat(JsonPath.<List<String>>read(body(list(Map.of("type", "SPENDING"))), "$.items[*].type"))
                .isNotEmpty().allMatch("SPENDING"::equals);
        assertThat(JsonPath.<List<String>>read(body(list(Map.of("importance", "IMPORTANT"))),
                "$.items[*].importance")).isNotEmpty().allMatch("IMPORTANT"::equals);
        MvcTestResult cards = list(Map.of("type", "CARDS"));
        assertThat(read(cards, "$.items")).isEqualTo(List.of());
        assertThat(read(cards, "$.totalItems")).isEqualTo(0);
        assertThat(read(cards, "$.counts.all")).isEqualTo(total);

        MvcTestResult first = list(Map.of("pageSize", "1"));
        MvcTestResult second = list(Map.of("pageSize", "1", "page", "1"));
        assertThat(JsonPath.<List<?>>read(body(first), "$.items")).hasSize(1);
        assertThat(read(first, "$.totalItems")).isEqualTo(total);
        assertThat(read(first, "$.items[0].id")).isNotEqualTo(read(second, "$.items[0].id"));
    }

    @Test
    void detailHasEvidenceSourcesAndRelated() throws Exception {
        seed();
        String spike = id(list(Map.of()), "CATEGORY_SPIKE");
        MvcTestResult detail = mvc.get().uri(INSIGHT, workspace, spike).with(ana()).exchange();
        assertThat(detail).hasStatus(HttpStatus.OK);
        assertThat(read(detail, "$.insight.title").toString()).startsWith("Seus gastos com alimentação fora aumentaram 200%");
        assertThat(read(detail, "$.figures[?(@.code == 'current')].value")).isEqualTo(List.of("900.00"));
        assertThat(read(detail, "$.figures[?(@.code == 'average')].value")).isEqualTo(List.of("300.00"));
        assertThat(read(detail, "$.series.kind")).isEqualTo("MONEY");
        assertThat(JsonPath.<List<?>>read(body(detail), "$.series.points")).hasSize(6);
        assertThat(read(detail, "$.contributors[0].amount.amount")).isEqualTo("900.00");
        assertThat(read(detail, "$.sources[0].source")).isEqualTo("analytics.categories");
        assertThat(read(detail, "$.dataSources")).isEqualTo(List.of("MANUAL"));
        assertThat(read(detail, "$.dimension.code")).isEqualTo("SPENDING");
        assertThat(read(detail, "$.dimension.label")).isEqualTo("Gastos");
        assertThat(JsonPath.<List<Boolean>>read(body(detail), "$.actions[*].available")).contains(true, false);
        // A alta total e a compra atípica falam da mesma categoria no mesmo período.
        assertThat(JsonPath.<List<String>>read(body(detail), "$.related[*].detector"))
                .contains("SPENDING_ABOVE_PATTERN", "UNUSUAL_EXPENSE").doesNotContain("CATEGORY_SPIKE");

        assertThat(mvc.get().uri(INSIGHT, workspace, UUID.randomUUID()).with(ana()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("INSIGHT_NOT_FOUND"));
    }

    @Test
    void seenDismissAndFeedbackAreIdempotent() throws Exception {
        seed();
        MvcTestResult all = list(Map.of());
        String spike = id(all, "CATEGORY_SPIKE");
        String uncategorized = id(all, "UNCATEGORIZED_SHARE");
        int total = ((Number) read(all, "$.counts.all")).intValue();

        MvcTestResult seen = mvc.post().uri(INSIGHT + "/seen", workspace, spike).with(ana()).exchange();
        assertThat(seen).hasStatus(HttpStatus.OK);
        assertThat(read(seen, "$.seen")).isEqualTo(true);
        String revision = revision(spike);
        assertThat(read(mvc.post().uri(INSIGHT + "/seen", workspace, spike).with(ana()).exchange(), "$.seen"))
                .isEqualTo(true);
        assertThat(revision(spike)).isEqualTo(revision);

        assertThat(read(feedback(spike, "\"USEFUL\""), "$.feedback")).isEqualTo("USEFUL");
        assertThat(read(feedback(spike, "null"), "$.feedback")).isNull();
        assertThat(mvc.put().uri(INSIGHT + "/feedback", workspace, spike).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": \"MAYBE\"}").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);

        MvcTestResult dismissed = mvc.post().uri(INSIGHT + "/dismiss", workspace, spike).with(ana()).exchange();
        assertThat(read(dismissed, "$.status")).isEqualTo("DISMISSED");
        assertThat(mvc.post().uri(INSIGHT + "/dismiss", workspace, spike).with(ana()).exchange())
                .hasStatus(HttpStatus.OK);
        assertThat(read(list(Map.of()), "$.counts.all")).isEqualTo(total - 1);
        assertThat(JsonPath.<List<String>>read(body(list(Map.of())), "$.items[*].id")).doesNotContain(spike);
        assertThat(JsonPath.<List<String>>read(body(list(Map.of("state", "DISMISSED"))), "$.items[*].id"))
                .containsExactly(spike);

        jdbc.update("update insights set status = 'RESOLVED' where id = ?::uuid", uncategorized);
        assertThat(mvc.post().uri(INSIGHT + "/dismiss", workspace, uncategorized).with(ana()).exchange())
                .hasStatus(HttpStatus.CONFLICT).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("INSIGHT_NOT_ACTIVE"));
    }

    @Test
    void highlightsAndRelated() throws Exception {
        seed();
        MvcTestResult highlights = mvc.get().uri(INSIGHTS + "/highlights", workspace).with(ana()).exchange();
        assertThat(highlights).hasStatus(HttpStatus.OK);
        assertThat(read(highlights, "$.state")).isEqualTo("READY");
        List<String> attention = JsonPath.read(body(highlights), "$.attention[*].importance");
        assertThat(attention).hasSizeBetween(1, 2).allMatch("IMPORTANT"::equals);
        assertThat(JsonPath.<List<?>>read(body(highlights), "$.changes")).hasSizeBetween(1, 3);
        assertThat(JsonPath.<List<String>>read(body(highlights), "$.changes[*].label")).contains("Alimentação fora");
        assertThat(((Number) read(highlights, "$.importantCount")).intValue()).isGreaterThanOrEqualTo(1);

        MvcTestResult byTransaction = mvc.get().uri(INSIGHTS + "/related", workspace).param("transactionId", restaurant)
                .with(ana()).exchange();
        assertThat(JsonPath.<List<String>>read(body(byTransaction), "$.items[*].detector"))
                .contains("CATEGORY_SPIKE", "UNUSUAL_EXPENSE");
        MvcTestResult byDimension = mvc.get().uri(INSIGHTS + "/related", workspace).param("dimension", "SPENDING")
                .with(ana()).exchange();
        assertThat(JsonPath.<List<String>>read(body(byDimension), "$.items[*].detector")).isNotEmpty();

        assertThat(mvc.get().uri(INSIGHTS + "/related", workspace).param("transactionId", restaurant)
                .param("dimension", "SPENDING").with(ana()).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri(INSIGHTS + "/related", workspace).with(ana()).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri(INSIGHTS + "/related", workspace).param("transactionId", UUID.randomUUID().toString())
                .with(ana()).exchange()).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void anotherWorkspacesInsightIsNotFoundAndUnchanged() throws Exception {
        seed();
        String spike = id(list(Map.of()), "CATEGORY_SPIKE");
        String bobs = read(mvc.post().uri(ME).with(bob()).exchange(), "$.workspaceId").toString();
        String before = row(spike);

        for (String path : List.of(INSIGHT, INSIGHT + "/seen", INSIGHT + "/dismiss")) {
            MvcTestResult foreign = path.equals(INSIGHT) ? mvc.get().uri(path, bobs, spike).with(bob()).exchange()
                    : mvc.post().uri(path, bobs, spike).with(bob()).exchange();
            assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("INSIGHT_NOT_FOUND"));
            assertThat(body(foreign)).doesNotContain("Restaurante").doesNotContain("alimentação");
        }
        assertThat(row(spike)).isEqualTo(before);
        assertThat(mvc.get().uri(INSIGHT, workspace, spike).with(bob()).exchange()).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void validationComesAfterAuthorization() throws Exception {
        for (Map<String, String> params : List.of(Map.of("type", "FOO"), Map.of("importance", "HIGH"),
                Map.of("state", "RESOLVED"), Map.of("pageSize", "51"), Map.of("pageSize", "0"),
                Map.of("page", "-1"))) {
            MvcTestResult invalid = list(params);
            assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"));
        }
        mvc.post().uri(ME).with(bob()).exchange();
        assertThat(mvc.get().uri(INSIGHTS, workspace).param("type", "FOO").with(bob()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void statesWithoutDataAndWithShortHistory() throws Exception {
        MvcTestResult empty = list(Map.of());
        assertThat(read(empty, "$.state")).isEqualTo("NO_DATA");
        assertThat(read(empty, "$.items")).isEqualTo(List.of());
        assertThat(read(mvc.get().uri(INSIGHTS + "/highlights", workspace).with(ana()).exchange(), "$.state"))
                .isEqualTo("NO_DATA");

        checking = createAccount();
        create("EXPENSE", period.month().atDay(1).toString(), "50.00", "Restaurante Sabor");
        IdentityTables.awaitQuiescence(jdbc);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(read(list(Map.of()), "$.state")).isEqualTo("INSUFFICIENT_HISTORY"));
    }

    private void seed() throws Exception {
        checking = createAccount();
        YearMonth p = period.month();
        for (int back = 5; back >= 0; back--) {
            YearMonth month = p.minusMonths(back);
            create("INCOME", month.atDay(1).toString(), "5000.00", "Salario Empresa Ficticia");
            String id = create("EXPENSE", month.atDay(1).toString(), back == 0 ? "900.00" : "300.00",
                    "Restaurante Sabor");
            if (back == 0) {
                restaurant = id;
            }
        }
        create("EXPENSE", p.atDay(1).toString(), "600.00", "Compra Loja Xyz");
        IdentityTables.awaitQuiescence(jdbc);
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            String detectors = body(list(Map.of()));
            return detectors.contains("CATEGORY_SPIKE") && detectors.contains("UNCATEGORIZED_SHARE")
                    && detectors.contains("UNUSUAL_EXPENSE") && detectors.contains("SPENDING_ABOVE_PATTERN");
        });
    }

    private MvcTestResult list(Map<String, String> params) {
        var request = mvc.get().uri(INSIGHTS, workspace).with(ana());
        params.forEach(request::param);
        return request.exchange();
    }

    private MvcTestResult feedback(String insight, String value) {
        MvcTestResult result = mvc.put().uri(INSIGHT + "/feedback", workspace, insight).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + value + "}").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return result;
    }

    private static String id(MvcTestResult list, String detector) throws Exception {
        List<String> ids = JsonPath.read(body(list), "$.items[?(@.detector == '" + detector + "')].id");
        assertThat(ids).as(detector).isNotEmpty();
        return ids.getFirst();
    }

    private String revision(String insight) {
        return jdbc.queryForObject("select revision || ' ' || updated_at::text from insights where id = ?::uuid",
                String.class, insight);
    }

    private String row(String insight) {
        return jdbc.queryForObject("select i::text from insights i where id = ?::uuid", String.class, insight);
    }

    private String createAccount() throws Exception {
        MvcTestResult result = mvc.post().uri(ACCOUNTS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private String create(String type, String day, String amount, String description) throws Exception {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, workspace).with(ana())
                .header("Idempotency-Key", "a-" + ++keys).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "%s", "description": "%s"}
                        """.formatted(type, checking, amount, day, description)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private static RequestPostProcessor ana() {
        return identity("sub-ana", "ana@example.com");
    }

    private static RequestPostProcessor bob() {
        return identity("sub-bob", "bob@example.com");
    }

    private static RequestPostProcessor identity(String subject, String email) {
        return jwt().jwt(token -> token.subject(subject).claim("iss", ISSUER).claim("email", email)
                .claim("name", "Nome"));
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }
}
