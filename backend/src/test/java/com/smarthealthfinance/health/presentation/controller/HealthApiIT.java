package com.smarthealthfinance.health.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.health.application.service.HealthInputLoader;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * API do Health (ADR-0019) sobre o snapshot real: valores exatos do {@code health-v2} sem cartões, estados, validação, evolução,
 * método, fontes e autorização.
 * <p>
 * Mesmo cenário do {@code HealthWorkerIT}: 3 meses fechados e o corrente com salário de 5.000 e despesa de 3.000 no
 * dia 1, reserva de 9.000 numa poupança fora do total → Cash Flow 100, Spending 100, Reserve 80, Consistency 100,
 * score 94 (valores que valem também no último dia do mês).
 */
class HealthApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String IMPORTS = "/api/v1/workspaces/{workspaceId}/imports";
    private static final String HEALTH = "/api/v1/workspaces/{workspaceId}/health";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HealthInputLoader loader;

    private String workspace;
    private String checking;
    private String savings;
    private YearMonth current;
    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        checking = createAccount("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}");
        savings = createAccount("{\"name\": \"Banco Norte\", \"type\": \"SAVINGS\", \"includedInTotal\": false}");
        current = YearMonth.from(loader.today());
    }

    @Test
    void summaryReturnsTheStoredSnapshotWithExactValues() throws Exception {
        seedHistory();

        MvcTestResult health = awaitReady(null);

        assertThat(health).hasStatus(HttpStatus.OK).bodyJson().isLenientlyEqualTo("""
                {"workspaceId": "%s", "state": "READY", "algorithmVersion": "health-v2", "currency": "BRL",
                 "period": {"month": "%s", "monthsUsed": %d},
                 "score": 94, "variation": 0, "estimated": false,
                 "coverage": {"available": 4, "total": 6, "weightPercent": 70},
                 "context": {"activeAccounts": 2, "uncategorizedShare": "0.0", "sources": ["MANUAL"]},
                 "freshness": {"state": "UP_TO_DATE"}}
                """.formatted(workspace, current, lastDay() ? 4 : 3));
        assertThat(read(health, "$.dimensions[*].dimension")).isEqualTo(
                List.of("CASH_FLOW", "SPENDING", "CREDIT", "DEBT", "RESERVE", "CONSISTENCY"));
        assertThat(read(health, "$.dimensions[*].score")).isEqualTo(java.util.Arrays.asList(100, 100, null, null, 80,
                100));
        assertThat(read(health, "$.dimensions[2].status")).isEqualTo("NOT_APPLICABLE");
        assertThat(read(health, "$.dimensions[2].reason")).isEqualTo("REQUIRES_CARDS");
        assertThat(read(health, "$.dimensions[4].label")).isEqualTo("Reserva");
        assertThat(read(health, "$.dimensions[4].weight")).isEqualTo(20);
        assertThat(read(health, "$.dimensions[4].attention")).isEqualTo(false);
        assertThat(read(health, "$.dimensions[4].evidence[?(@.code == 'coverageMonths')].value"))
                .isEqualTo(List.of("3.00"));
        assertThat(read(health, "$.dimensions[4].evidence[?(@.code == 'reserveBalance')].source"))
                .isEqualTo(List.of("overview.account-balances"));
        assertThat(read(health, "$.dimensions[4].explanation").toString())
                .startsWith("Sua reserva cobre ≈ 3,0 meses da média de despesas (R$ 3.000,00/mês");
        assertThat(health).bodyJson().isLenientlyEqualTo("""
                {"nextSteps": [{"kind": "BUILD_RESERVE", "text": "Fortalecer a reserva",
                  "evidence": "Faltam R$ 9.000,00 para 6 meses de despesas.", "categoryCode": null, "month": null,
                  "amount": {"amount": "9000.00", "currency": "BRL"}}]}
                """);
        assertThat(read(health, "$.factors[*].direction")).isEqualTo(List.of("SUSTAINING", "SUSTAINING",
                "SUSTAINING"));
        assertThat(read(health, "$.summary").toString()).startsWith("Seu resultado combina 4 de 6 dimensões.");
    }

    @Test
    void recalculatingStillReturnsTheLastSnapshot() throws Exception {
        seedHistory();
        awaitReady(null);
        // Marca sem vencer: o job não pega antes do fim do teste.
        jdbc.update("update health_workspaces set dirty_since = now(), due_at = now() + interval '1 hour'"
                + " where workspace_id = ?::uuid", workspace);
        try {
            MvcTestResult health = get(HEALTH, null);
            assertThat(read(health, "$.state")).isEqualTo("READY");
            assertThat(read(health, "$.score")).isEqualTo(94);
            assertThat(read(health, "$.freshness.state")).isEqualTo("RECALCULATING");
        }
        finally {
            jdbc.update("update health_workspaces set dirty_since = null, due_at = null where workspace_id = ?::uuid",
                    workspace);
        }
    }

    @Test
    void withoutClosedMonthsTheDimensionsComeWithoutAnOverallScore() throws Exception {
        createTransaction("INCOME", checking, current.atDay(1), "5000.00", "Empresa Ficticia");
        IdentityTables.awaitQuiescence(jdbc);

        MvcTestResult health = await().atMost(Duration.ofSeconds(30)).until(() -> get(HEALTH, null),
                r -> !"NO_DATA".equals(readQuietly(r, "$.state")));

        assertThat(health).bodyJson().isLenientlyEqualTo("""
                {"state": "INSUFFICIENT_DATA", "score": null, "summary": null, "estimated": true,
                 "confidence": "LOW"}
                """);
        assertThat(read(health, "$.dimensions[0].reason")).isEqualTo(lastDay() ? null : "NO_CLOSED_MONTH");
    }

    @Test
    void monthWithoutSnapshotIsNoData() throws Exception {
        MvcTestResult empty = get(HEALTH, null);
        assertThat(empty).hasStatus(HttpStatus.OK).bodyJson().isLenientlyEqualTo("""
                {"state": "NO_DATA", "score": null, "confidence": null, "coverage": null, "context": null,
                 "dimensions": [], "factors": [], "nextSteps": [], "freshness": {"state": "UP_TO_DATE"}}
                """);
        assertThat(read(get(HEALTH, current.minusMonths(20).toString()), "$.period.month"))
                .isEqualTo(current.minusMonths(20).toString());
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        for (MvcTestResult invalid : List.of(get(HEALTH, "2099-01"), get(HEALTH, "abc"),
                get(HEALTH + "/evolution?months=13", null), get(HEALTH + "/evolution?months=0", null),
                get(HEALTH + "/evolution", "2099-01"))) {
            assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"));
        }
        assertThat(read(get(HEALTH, "2099-01"), "$.details[0].code")).isEqualTo("FUTURE");
        assertThat(read(get(HEALTH + "/evolution?months=13", null), "$.details[0].code")).isEqualTo("OUT_OF_RANGE");
    }

    @Test
    void evolutionHasOneItemPerMonthEvenWithoutSnapshot() throws Exception {
        seedHistory();
        awaitReady(null);

        MvcTestResult evolution = get(HEALTH + "/evolution", null);

        assertThat(read(evolution, "$.months[*].month")).isEqualTo(List.of(current.minusMonths(5).toString(),
                current.minusMonths(4).toString(), current.minusMonths(3).toString(),
                current.minusMonths(2).toString(), current.minusMonths(1).toString(), current.toString()));
        assertThat(read(evolution, "$.months[*].state")).isEqualTo(List.of("NO_DATA", "NO_DATA", "READY", "READY",
                "READY", "READY"));
        // Mais antigo: só Cash Flow (100) e Reserve (80) → (2.500 + 1.600) ÷ 45 = 91,1 → 91, estimado.
        assertThat(evolution).bodyJson().isLenientlyEqualTo("""
                {"months": [{}, {}, {"month": "%s", "state": "READY", "score": 91, "variation": null,
                                 "estimated": true, "partial": false}, {}, {}, {}]}
                """.formatted(current.minusMonths(3)));
        assertThat(read(evolution, "$.months[5].score")).isEqualTo(94);
        assertThat(read(evolution, "$.to")).isEqualTo(current.toString());

        MvcTestResult earlier = get(HEALTH + "/evolution?months=2", current.minusMonths(2).toString());
        assertThat(read(earlier, "$.months[*].month")).isEqualTo(List.of(current.minusMonths(3).toString(),
                current.minusMonths(2).toString()));
    }

    @Test
    void methodDescribesWeightsCurvesAndExclusions() throws Exception {
        MvcTestResult method = get(HEALTH + "/method", null);

        assertThat(method).hasStatus(HttpStatus.OK);
        assertThat(read(method, "$.algorithmVersion")).isEqualTo("health-v2");
        List<Integer> weights = JsonPath.read(body(method), "$.dimensions[*].weight");
        assertThat(weights).containsExactly(25, 15, 15, 15, 20, 10);
        assertThat(read(method, "$.dimensions[0].curves[0].points")).isEqualTo(List.of(
                Map.of("x", "-0.1", "score", "0"), Map.of("x", "0", "score", "30"), Map.of("x", "0.1", "score", "60"),
                Map.of("x", "0.2", "score", "80"), Map.of("x", "0.4", "score", "100")));
        assertThat(read(method, "$.dimensions[2].applicable")).isEqualTo(true);
        assertThat(read(method, "$.confidence[*].level")).isEqualTo(List.of("HIGH", "MEDIUM", "LOW"));
        assertThat((List<String>) JsonPath.read(body(method), "$.exclusions")).contains("Investimentos", "Seguros",
                "Empréstimos complexos");
    }

    @Test
    void sourcesListManualEntriesAndImportFormats() throws Exception {
        LocalDate day = current.minusMonths(1).atDay(2);
        createTransaction("INCOME", checking, day, "5000.00", "Empresa Ficticia");
        String csv = "data;descricao;valor;id\n%s;Tarifa Teste;-1,00;t1\n"
                .formatted(day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        MvcTestResult upload = mvc.post().uri(IMPORTS, workspace).multipart()
                .file(new MockMultipartFile("file", "extrato.csv", "application/octet-stream",
                        csv.getBytes(StandardCharsets.UTF_8)))
                .param("accountId", checking).header("Idempotency-Key", "imp-1").with(ana()).exchange();
        assertThat(upload).hasStatus(HttpStatus.CREATED);
        String importId = read(upload, "$.id").toString();
        assertThat(mvc.post().uri(IMPORTS + "/{id}/confirm", workspace, importId).with(ana()).exchange())
                .hasStatus(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(30)).until(() -> read(mvc.get().uri(IMPORTS + "/{id}", workspace,
                importId).with(ana()).exchange(), "$.status").equals("COMPLETED"));

        MvcTestResult health = awaitHealth(r -> List.of("CSV", "MANUAL").equals(readQuietly(r, "$.context.sources")));
        assertThat(read(health, "$.context.sources")).isEqualTo(List.of("CSV", "MANUAL"));
    }

    @Test
    void everyRouteRequiresMembership() {
        mvc.post().uri(ME).with(bob()).exchange();
        for (String route : List.of(HEALTH, HEALTH + "/evolution", HEALTH + "/method")) {
            assertThat(mvc.get().uri(route, workspace).with(bob()).exchange()).hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
            assertThat(mvc.get().uri(route, workspace).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }

    private boolean lastDay() {
        return loader.today().equals(current.atEndOfMonth());
    }

    private void seedHistory() throws Exception {
        createTransaction("INCOME", savings, current.minusMonths(3).atDay(1), "9000.00", "Empresa Ficticia");
        for (int back = 3; back >= 0; back--) {
            LocalDate day = current.minusMonths(back).atDay(1);
            createTransaction("INCOME", checking, day, "5000.00", "Empresa Ficticia");
            createTransaction("EXPENSE", checking, day, "3000.00", "Aluguel Casa");
        }
        IdentityTables.awaitQuiescence(jdbc);
    }

    private MvcTestResult awaitReady(String month) {
        return await().atMost(Duration.ofSeconds(30)).until(() -> get(HEALTH, month),
                r -> "READY".equals(readQuietly(r, "$.state")) && "UP_TO_DATE".equals(
                        readQuietly(r, "$.freshness.state")));
    }

    private MvcTestResult awaitHealth(java.util.function.Predicate<MvcTestResult> condition) {
        return await().atMost(Duration.ofSeconds(30)).until(() -> get(HEALTH, null), condition);
    }

    private MvcTestResult get(String route, String month) {
        var request = mvc.get().uri(route, workspace).with(ana());
        return month == null ? request.exchange() : request.param("month", month).exchange();
    }

    private String createAccount(String body) throws Exception {
        MvcTestResult result = mvc.post().uri(ACCOUNTS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private void createTransaction(String type, String account, LocalDate day, String amount, String description)
            throws Exception {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, workspace).with(ana())
                .header("Idempotency-Key", "a-" + ++keys).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "%s", "description": "%s"}
                        """.formatted(type, account, amount, day, description)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
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

    private static Object readQuietly(MvcTestResult result, String path) {
        try {
            return read(result, path);
        }
        catch (Exception unreadable) {
            return null;
        }
    }
}
