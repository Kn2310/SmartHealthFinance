package com.smarthealthfinance.goals.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * API de objetivos (ADR-0034, ADR-0035): progresso como saldo do destino, aportes como transferências idempotentes,
 * reserva espelhando as contas SAVINGS, exclusividade do destino, status e eventos sem dados sensíveis.
 */
@ExtendWith(OutputCaptureExtension.class)
class GoalApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String WORKSPACE = "/api/v1/workspaces/{workspaceId}";
    private static final String GOALS = WORKSPACE + "/goals";
    private static final String GOAL = GOALS + "/{goalId}";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    private static final String GOAL_NAME = "Viagem Sigilosa para Lisboa";

    @Autowired
    JdbcTemplate jdbc;

    private String workspace;
    private String aurora;
    private String trip;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId");
        aurora = account("Banco Aurora", "CHECKING");
        trip = account("Caixinha Lisboa", "PAYMENT");
    }

    // --- progresso e aportes (fato) ---

    @Test
    void progressIsTheDestinationBalanceAndContributionsAreTheTransfers(CapturedOutput output) throws Exception {
        // "Aporte inicial de R$ 100,00 + 6 aportes de R$ 700,00" (D-GoalDetail): R$ 4.300 de R$ 12.000, 36%.
        transfer(aurora, trip, "100.00", TODAY.minusDays(1));
        String goal = createLisbon();
        for (int i = 0; i < 6; i++) {
            assertThat(contribute(goal, UUID.randomUUID().toString(), aurora, "700.00")).hasStatus(HttpStatus.CREATED);
        }

        MvcTestResult detail = mvc.get().uri(GOAL, workspace, goal).with(ana()).exchange();
        assertThat(detail).hasStatus(HttpStatus.OK);
        assertThat(read(detail, "$.progress.basis")).isEqualTo("FACT");
        assertThat(read(detail, "$.progress.saved.amount")).isEqualTo("4300.00");
        assertThat(read(detail, "$.progress.remaining.amount")).isEqualTo("7700.00");
        assertThat((Integer) JsonPath.read(body(detail), "$.progress.percent")).isEqualTo(36);
        assertThat((Boolean) JsonPath.read(body(detail), "$.progress.reached")).isFalse();
        assertThat(read(detail, "$.progress.destinationState")).isEqualTo("OK");

        MvcTestResult history = mvc.get().uri(GOAL + "/contributions", workspace, goal).with(ana()).exchange();
        assertThat(history).hasStatus(HttpStatus.OK);
        assertThat(read(history, "$.openingBalance.amount.amount")).isEqualTo("100.00");
        assertThat(read(history, "$.openingBalance.date")).isEqualTo(TODAY.minusDays(1).toString());
        List<String> kinds = JsonPath.read(body(history), "$.movements[*].kind");
        assertThat(kinds).hasSize(6).containsOnly("CONTRIBUTION");
        List<String> sources = JsonPath.read(body(history), "$.movements[*].counterpartAccountId");
        assertThat(sources).containsOnly(aurora);

        // O aporte é uma transferência: nem despesa nem receita, e o saldo total não muda.
        Integer expenses = jdbc.queryForObject(
                "select count(*) from transactions where type <> 'TRANSFER' and workspace_id = ?::uuid",
                Integer.class, workspace);
        assertThat(expenses).isZero();
        assertThat(output).doesNotContain(GOAL_NAME).doesNotContain("700.00");
    }

    @Test
    void withdrawalsAndOtherMovementsChangeTheProgressButAreNotContributions() throws Exception {
        String goal = createLisbon();
        contribute(goal, UUID.randomUUID().toString(), aurora, "1000.00");
        transfer(trip, aurora, "300.00", TODAY);

        MvcTestResult detail = mvc.get().uri(GOAL, workspace, goal).with(ana()).exchange();
        assertThat(read(detail, "$.progress.saved.amount")).isEqualTo("700.00");

        MvcTestResult history = mvc.get().uri(GOAL + "/contributions", workspace, goal).with(ana()).exchange();
        List<String> kinds = JsonPath.read(body(history), "$.movements[*].kind");
        assertThat(kinds).containsExactlyInAnyOrder("CONTRIBUTION", "WITHDRAWAL");
        List<String> recent = JsonPath.read(body(mvc.get().uri(GOALS + "/recent-contributions", workspace)
                .with(ana()).exchange()), "$.contributions[*].amount.amount");
        assertThat(recent).containsExactly("1000.00");
    }

    @Test
    void contributionIsIdempotentByKey() throws Exception {
        String goal = createLisbon();
        String key = UUID.randomUUID().toString();

        MvcTestResult first = contribute(goal, key, aurora, "700.00");
        MvcTestResult replay = contribute(goal, key, aurora, "700.00");
        MvcTestResult reused = contribute(goal, key, aurora, "701.00");

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(first.getResponse().getHeader("Location")).contains("/transactions/" + read(first, "$.id"));
        assertThat(read(first, "$.type")).isEqualTo("TRANSFER");
        assertThat(read(first, "$.destinationAccountId")).isEqualTo(trip);
        assertThat(read(first, "$.description")).isEqualTo("Aporte · " + GOAL_NAME);
        assertThat(replay).hasStatus(HttpStatus.OK);
        assertThat(read(replay, "$.id")).isEqualTo(read(first, "$.id"));
        assertThat(reused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(1);
    }

    @Test
    void contributionValidatesDateDestinationAndStatus() throws Exception {
        String goal = createLisbon();

        assertThat(code(contribute(goal, aurora, null, "1.00", TODAY.plusDays(1)))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(contribute(goal, aurora, null, "1.00", TODAY.minusDays(1)))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(contribute(goal, aurora, aurora, "1.00", TODAY))).isEqualTo("VALIDATION_FAILED");

        assertThat(mvc.post().uri(GOAL + "/complete", workspace, goal).with(ana())).hasStatus(HttpStatus.OK);
        MvcTestResult closed = contribute(goal, UUID.randomUUID().toString(), aurora, "1.00");
        assertThat(closed).hasStatus(HttpStatus.CONFLICT);
        assertThat(code(closed)).isEqualTo("GOAL_STATUS_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isZero();
    }

    // --- reserva (espelho do Reserve do Health) ---

    @Test
    void reserveMirrorsTheActiveSavingsAccountsAndIsUnique() throws Exception {
        String norte = account("Banco Norte", "SAVINGS");
        String norte2 = account("Banco Norte 2", "SAVINGS");
        adjust(norte, "6000.00");
        adjust(norte2, "4000.00");

        MvcTestResult reserve = create(reserveJson());
        assertThat(reserve).hasStatus(HttpStatus.CREATED);
        String goal = read(reserve, "$.id");
        assertThat(read(reserve, "$.destination.kind")).isEqualTo("RESERVE");
        assertThat(read(reserve, "$.progress.saved.amount")).isEqualTo("10000.00");
        assertThat((Integer) JsonPath.read(body(reserve), "$.progress.percent")).isEqualTo(40);

        MvcTestResult second = create(reserveJson());
        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(code(second)).isEqualTo("GOAL_RESERVE_ALREADY_EXISTS");

        // Aporte na reserva: destino SAVINGS obrigatório; de outra SAVINGS não conta como aporte.
        assertThat(code(contribute(goal, aurora, null, "500.00", TODAY))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(contribute(goal, aurora, trip, "500.00", TODAY))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(contribute(goal, norte2, norte, "500.00", TODAY))).isEqualTo("VALIDATION_FAILED");
        assertThat(contribute(goal, aurora, norte, "500.00", TODAY)).hasStatus(HttpStatus.CREATED);

        MvcTestResult detail = mvc.get().uri(GOAL, workspace, goal).with(ana()).exchange();
        assertThat(read(detail, "$.progress.saved.amount")).isEqualTo("10500.00");

        // Arquivar uma SAVINGS tira o saldo dela da reserva, como no Health.
        assertThat(mvc.post().uri(WORKSPACE + "/accounts/{id}/archive", workspace, norte2).with(ana()))
                .hasStatus(HttpStatus.OK);
        detail = mvc.get().uri(GOAL, workspace, goal).with(ana()).exchange();
        assertThat(read(detail, "$.progress.saved.amount")).isEqualTo("6500.00");
    }

    @Test
    void reserveWithoutSavingsAccountHasZeroProgressAndOthersCannotUseSavings() throws Exception {
        MvcTestResult reserve = create(reserveJson());
        assertThat(read(reserve, "$.progress.saved.amount")).isEqualTo("0.00");
        assertThat(read(reserve, "$.progress.destinationState")).isEqualTo("NO_RESERVE_ACCOUNT");

        String norte = account("Banco Norte", "SAVINGS");
        MvcTestResult savings = create(goalJson("Outro", norte, null));
        assertThat(savings).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(savings)).contains("SAVINGS_NOT_ALLOWED");

        MvcTestResult withAccount = create(reserveJson().replace("\"type\"",
                "\"destinationAccountId\": \"" + trip + "\", \"type\""));
        assertThat(withAccount).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(withAccount)).contains("NOT_ALLOWED");
    }

    // --- destino, edição, plano e status ---

    @Test
    void destinationIsExclusiveAmongOpenGoals() throws Exception {
        String goal = createLisbon();

        MvcTestResult taken = create(goalJson("Outro", trip, null));
        assertThat(taken).hasStatus(HttpStatus.CONFLICT);
        assertThat(code(taken)).isEqualTo("GOAL_DESTINATION_IN_USE");

        assertThat(mvc.post().uri(GOAL + "/archive", workspace, goal).with(ana())).hasStatus(HttpStatus.OK);
        assertThat(create(goalJson("Outro", trip, null))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void editApplyPlanAndStatusChangesEmitEventsWithoutSensitiveData() throws Exception {
        String goal = createLisbon();
        String deadline = YearMonth.from(TODAY).plusMonths(10).toString();

        MvcTestResult edit = mvc.put().uri(GOAL, workspace, goal).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "targetAmount": {"amount": "13000.00", "currency": "BRL"},
                         "plannedMonthly": {"amount": "700.00", "currency": "BRL"}, "deadline": "%s",
                         "sourceAccountId": "%s", "destinationAccountId": "%s"}
                        """.formatted(GOAL_NAME, deadline, aurora, trip)).exchange();
        assertThat(edit).hasStatus(HttpStatus.OK);
        assertThat(read(edit, "$.targetAmount.amount")).isEqualTo("13000.00");

        MvcTestResult plan = mvc.put().uri(GOAL + "/plan", workspace, goal).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"plannedMonthly": {"amount": "780.00", "currency": "BRL"}, "deadline": "%s"}
                        """.formatted(deadline)).exchange();
        assertThat(plan).hasStatus(HttpStatus.OK);
        assertThat(read(plan, "$.plannedMonthly.amount")).isEqualTo("780.00");
        assertThat(read(plan, "$.targetAmount.amount")).isEqualTo("13000.00");
        assertThat(read(plan, "$.deadline")).isEqualTo(deadline);
        // Repetir o mesmo plano não emite evento.
        mvc.put().uri(GOAL + "/plan", workspace, goal).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"plannedMonthly": {"amount": "780.00", "currency": "BRL"}, "deadline": "%s"}
                        """.formatted(deadline)).exchange();

        assertThat(mvc.post().uri(GOAL + "/pause", workspace, goal).with(ana())).hasStatus(HttpStatus.OK);
        assertThat(mvc.post().uri(GOAL + "/pause", workspace, goal).with(ana())).hasStatus(HttpStatus.OK);
        assertThat(mvc.post().uri(GOAL + "/resume", workspace, goal).with(ana())).hasStatus(HttpStatus.OK);

        List<Map<String, Object>> events = jdbc.queryForList("""
                select event_type, payload::text as payload from outbox_events
                where aggregate_type = 'Goal' order by occurred_at, id
                """);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("goal.created",
                "goal.plan-changed", "goal.plan-changed", "goal.status-changed", "goal.status-changed");
        assertThat(events.get(1).get("payload").toString()).contains("targetAmount").contains("deadline");
        assertThat(events.get(2).get("payload").toString()).contains("plannedMonthly");
        assertThat(events).allSatisfy(e -> assertThat(e.get("payload").toString())
                .doesNotContain(GOAL_NAME).doesNotContain("\"amount\"").doesNotContain(".00"));
    }

    @Test
    void completingFreezesTheProgressAndReleasesTheDestination() throws Exception {
        String goal = createLisbon();
        contribute(goal, UUID.randomUUID().toString(), aurora, "1200.00");

        MvcTestResult done = mvc.post().uri(GOAL + "/complete", workspace, goal).with(ana()).exchange();
        assertThat(done).hasStatus(HttpStatus.OK);
        assertThat(read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat(read(done, "$.completedAmount.amount")).isEqualTo("1200.00");

        transfer(trip, aurora, "1200.00", TODAY);
        MvcTestResult detail = mvc.get().uri(GOAL, workspace, goal).with(ana()).exchange();
        assertThat(read(detail, "$.progress.saved.amount")).isEqualTo("1200.00");

        MvcTestResult resume = mvc.post().uri(GOAL + "/resume", workspace, goal).with(ana()).exchange();
        assertThat(resume).hasStatus(HttpStatus.CONFLICT);
        assertThat(code(resume)).isEqualTo("GOAL_STATUS_CONFLICT");
        assertThat(create(goalJson("Outro", trip, null))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void listSummarizesTheOpenGoalsAndFiltersByStatus() throws Exception {
        String norte = account("Banco Norte", "SAVINGS");
        adjust(norte, "10000.00");
        create(reserveJson());
        String lisbon = createLisbon();
        contribute(lisbon, UUID.randomUUID().toString(), aurora, "4300.00");
        String other = account("Caixinha Carro", "OTHER");
        String car = read(create(goalJson("Carro", other, null)), "$.id");
        mvc.post().uri(GOAL + "/pause", workspace, car).with(ana()).exchange();
        String done = read(create(goalJson("Feito", account("Caixinha Feita", "OTHER"), null)), "$.id");
        mvc.post().uri(GOAL + "/complete", workspace, done).with(ana()).exchange();

        MvcTestResult list = mvc.get().uri(GOALS, workspace).with(ana()).exchange();
        assertThat(list).hasStatus(HttpStatus.OK);
        assertThat(read(list, "$.summary.savedTotal.amount")).isEqualTo("14300.00");
        // Reserva 800 + Lisboa 700; o pausado não planeja.
        assertThat(read(list, "$.summary.plannedMonthlyTotal.amount")).isEqualTo("1500.00");
        assertThat((Integer) JsonPath.read(body(list), "$.summary.activeCount")).isEqualTo(2);
        List<String> names = JsonPath.read(body(list), "$.goals[*].name");
        assertThat(names).containsExactly("Carro", GOAL_NAME, "Reserva de emergência");

        MvcTestResult completed = mvc.get().uri(GOALS, workspace).param("status", "COMPLETED").with(ana()).exchange();
        List<String> completedNames = JsonPath.read(body(completed), "$.goals[*].name");
        assertThat(completedNames).containsExactly("Feito");
        assertThat(read(completed, "$.summary.savedTotal.amount")).isEqualTo("14300.00");

        MvcTestResult invalid = mvc.get().uri(GOALS, workspace).param("status", "NOPE").with(ana()).exchange();
        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createValidatesDeadlineAndOrigin() throws Exception {
        MvcTestResult past = create("""
                {"name": "X", "type": "CUSTOM", "targetAmount": {"amount": "10.00", "currency": "BRL"},
                 "plannedMonthly": {"amount": "1.00", "currency": "BRL"}, "deadline": "%s",
                 "destinationAccountId": "%s"}
                """.formatted(YearMonth.from(TODAY), trip));
        assertThat(past).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(past)).contains("PAST");

        MvcTestResult insight = create("""
                {"name": "Guardar a diferença do delivery", "type": "CUSTOM",
                 "targetAmount": {"amount": "1800.00", "currency": "BRL"},
                 "plannedMonthly": {"amount": "300.00", "currency": "BRL"}, "destinationAccountId": "%s",
                 "origin": {"kind": "INSIGHT", "insightId": "%s"}}
                """.formatted(trip, UUID.randomUUID()));
        assertThat(insight).hasStatus(HttpStatus.CREATED);
        assertThat(read(insight, "$.origin.kind")).isEqualTo("INSIGHT");
        assertThat(read(insight, "$.progress.saved.amount")).isEqualTo("0.00");

        MvcTestResult noInsight = create("""
                {"name": "X", "type": "CUSTOM", "targetAmount": {"amount": "10.00", "currency": "BRL"},
                 "plannedMonthly": {"amount": "1.00", "currency": "BRL"}, "destinationAccountId": "%s",
                 "origin": {"kind": "INSIGHT"}}
                """.formatted(account("Outra", "OTHER")));
        assertThat(noInsight).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ---

    private String createLisbon() throws Exception {
        MvcTestResult result = create(goalJson(GOAL_NAME, trip, aurora));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id");
    }

    private static String goalJson(String name, String destination, String source) {
        return """
                {"name": "%s", "type": "PURCHASE", "targetAmount": {"amount": "12000.00", "currency": "BRL"},
                 "plannedMonthly": {"amount": "700.00", "currency": "BRL"}, "destinationAccountId": "%s",
                 "sourceAccountId": %s}
                """.formatted(name, destination, source == null ? "null" : "\"" + source + "\"");
    }

    private static String reserveJson() {
        return """
                {"name": "Reserva de emergência", "type": "EMERGENCY_RESERVE",
                 "targetAmount": {"amount": "25152.00", "currency": "BRL"},
                 "plannedMonthly": {"amount": "800.00", "currency": "BRL"}, "origin": {"kind": "HEALTH"}}
                """;
    }

    private MvcTestResult create(String json) {
        return mvc.post().uri(GOALS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
    }

    private MvcTestResult contribute(String goal, String key, String source, String amount) {
        return mvc.post().uri(GOAL + "/contributions", workspace, goal).with(ana())
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key).content("""
                        {"sourceAccountId": "%s", "amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "%s"}
                        """.formatted(source, amount, TODAY)).exchange();
    }

    private MvcTestResult contribute(String goal, String source, String destination, String amount, LocalDate on) {
        return mvc.post().uri(GOAL + "/contributions", workspace, goal).with(ana())
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                        {"sourceAccountId": "%s", "destinationAccountId": %s,
                         "amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "%s"}
                        """.formatted(source, destination == null ? "null" : "\"" + destination + "\"", amount, on))
                .exchange();
    }

    private String account(String name, String type) throws Exception {
        MvcTestResult result = mvc.post().uri(WORKSPACE + "/accounts", workspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "type": "%s"}
                        """.formatted(name, type)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id");
    }

    private void transfer(String from, String to, String amount, LocalDate on) {
        assertThat(transaction("""
                {"type": "TRANSFER", "accountId": "%s", "destinationAccountId": "%s",
                 "amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "%s", "description": "Transferência"}
                """.formatted(from, to, amount, on))).hasStatus(HttpStatus.CREATED);
    }

    private void adjust(String account, String amount) {
        assertThat(transaction("""
                {"type": "ADJUSTMENT", "accountId": "%s", "adjustmentDirection": "INCREASE",
                 "amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "%s", "description": "Saldo inicial"}
                """.formatted(account, amount, TODAY.minusDays(10)))).hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult transaction(String json) {
        return mvc.post().uri(WORKSPACE + "/transactions", workspace).with(ana())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json).exchange();
    }

    private static String code(MvcTestResult result) throws Exception {
        return read(result, "$.code");
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String read(MvcTestResult result, String path) throws Exception {
        Object value = JsonPath.read(body(result), path);
        return value == null ? null : value.toString();
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(token -> token.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Ana"));
    }
}
