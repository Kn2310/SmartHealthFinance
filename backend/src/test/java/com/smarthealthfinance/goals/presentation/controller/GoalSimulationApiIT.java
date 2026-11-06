package com.smarthealthfinance.goals.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.identity.IdentityTables;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Simulação, prévia e gráfico dos objetivos (ADR-0036): sempre {@code SIMULATED}, nada é gravado, o objetivo não muda
 * e fato e estimativa ficam em séries separadas. Sem Forecast pronto (Workspace novo), a previsão é
 * {@code NO_FORECAST} e só os fatos aparecem.
 */
class GoalSimulationApiIT extends IntegrationTest {

    private static final String WORKSPACE = "/api/v1/workspaces/{workspaceId}";
    private static final String GOALS = WORKSPACE + "/goals";
    private static final String GOAL = GOALS + "/{goalId}";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    private static final YearMonth START = YearMonth.from(TODAY).plusMonths(1);

    @Autowired
    JdbcTemplate jdbc;

    private String workspace;
    private String aurora;
    private String trip;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri("/api/v1/users/me").with(ana()).exchange(), "$.workspaceId");
        aurora = account("Banco Aurora", "CHECKING");
        trip = account("Caixinha Lisboa", "PAYMENT");
    }

    @Test
    void simulatesScenariosWithoutChangingAnything() throws Exception {
        transfer(aurora, trip, "4300.00", TODAY.minusDays(1));
        String goal = createLisbon();
        IdentityTables.awaitQuiescence(jdbc);
        String outbox = snapshot();

        MvcTestResult result = mvc.post().uri(GOAL + "/simulations", workspace, goal).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"scenarios": [
                          {"monthlyAmount": {"amount": "700.00", "currency": "BRL"}},
                          {"monthlyAmount": {"amount": "780.00", "currency": "BRL"}},
                          {"monthlyAmount": {"amount": "900.00", "currency": "BRL"}}]}
                        """).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(read(result, "$.basis")).isEqualTo("SIMULATED");
        assertThat(read(result, "$.remaining.amount")).isEqualTo("7700.00");
        assertThat(read(result, "$.start")).isEqualTo(START.toString());
        List<String> completions = JsonPath.read(body(result), "$.scenarios[*].completion");
        assertThat(completions).containsExactly(START.plusMonths(10).toString(), START.plusMonths(9).toString(),
                START.plusMonths(8).toString());
        List<Integer> counts = JsonPath.read(body(result), "$.scenarios[*].contributions");
        assertThat(counts).containsExactly(11, 10, 9);
        List<String> assumptions = JsonPath.read(body(result), "$.assumptions");
        assertThat(assumptions).containsExactly("EQUAL_MONTHLY_CONTRIBUTIONS", "NO_YIELD");
        assertThat(read(result, "$.scenarios[1].deadline.month")).isEqualTo(START.plusMonths(9).toString());
        assertThat((Boolean) JsonPath.read(body(result), "$.scenarios[1].deadline.within")).isTrue();
        assertThat(read(result, "$.scenarios[1].requiredMonthlyForDeadline.amount")).isEqualTo("770.00");
        IdentityTables.awaitQuiescence(jdbc);
        assertThat(snapshot()).as("simular não grava nada").isEqualTo(outbox);

        MvcTestResult invalid = mvc.post().uri(GOAL + "/simulations", workspace, goal).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"scenarios": [{"reduction": {"categoryCode": "NOPE", "amount": {"amount": "1.00", "currency": "BRL"}}}]}
                        """).exchange();
        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
        MvcTestResult empty = mvc.post().uri(GOAL + "/simulations", workspace, goal).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scenarios\": []}").exchange();
        assertThat(empty).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void previewShowsAlreadySavedAndTheSimulatedPlan() throws Exception {
        transfer(aurora, trip, "100.00", TODAY.minusDays(1));

        MvcTestResult preview = mvc.post().uri(GOALS + "/simulations/preview", workspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "CUSTOM", "targetAmount": {"amount": "1900.00", "currency": "BRL"},
                         "plannedMonthly": {"amount": "300.00", "currency": "BRL"}, "destinationAccountId": "%s"}
                        """.formatted(trip)).exchange();

        assertThat(preview).hasStatus(HttpStatus.OK);
        assertThat(read(preview, "$.alreadySaved.amount")).isEqualTo("100.00");
        assertThat(read(preview, "$.suggestedTarget")).isNull();
        assertThat(read(preview, "$.simulation.basis")).isEqualTo("SIMULATED");
        assertThat((Integer) JsonPath.read(body(preview), "$.simulation.scenarios[0].contributions")).isEqualTo(6);
        assertThat(read(preview, "$.simulation.scenarios[0].firstContribution")).isEqualTo(START.toString());
        assertThat(jdbc.queryForObject("select count(*) from goals", Integer.class)).isZero();
    }

    @Test
    void reservePreviewWithoutHistoryHasNoSuggestedTarget() throws Exception {
        MvcTestResult preview = mvc.post().uri(GOALS + "/simulations/preview", workspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "EMERGENCY_RESERVE", "plannedMonthly": {"amount": "800.00", "currency": "BRL"}}
                        """).exchange();

        assertThat(preview).hasStatus(HttpStatus.OK);
        assertThat(read(preview, "$.alreadySaved.amount")).isEqualTo("0.00");
        assertThat(read(preview, "$.suggestedTarget")).isNull();
        assertThat(read(preview, "$.simulation")).isNull();
    }

    @Test
    void projectionSeparatesFactsFromEstimatesAndGoalShowsOnlyFactsWithoutForecast() throws Exception {
        String goal = createLisbon();
        assertThat(contribute(goal, "700.00")).hasStatus(HttpStatus.CREATED);

        MvcTestResult detail = mvc.get().uri(GOAL, workspace, goal).with(ana()).exchange();
        assertThat(read(detail, "$.forecast.basis")).isEqualTo("ESTIMATED");
        assertThat(read(detail, "$.forecast.status")).isEqualTo("NO_FORECAST");
        assertThat(read(detail, "$.forecast.reason")).isEqualTo("FORECAST_NOT_READY");
        assertThat(read(detail, "$.forecast.completion")).isNull();

        MvcTestResult projection = mvc.get().uri(GOAL + "/projection", workspace, goal).with(ana()).exchange();
        assertThat(projection).hasStatus(HttpStatus.OK);
        assertThat(read(projection, "$.actual.basis")).isEqualTo("FACT");
        assertThat(read(projection, "$.projected.basis")).isEqualTo("ESTIMATED");
        List<String> months = JsonPath.read(body(projection), "$.actual.points[*].month");
        assertThat(months).containsExactly(YearMonth.from(TODAY).toString());
        assertThat(read(projection, "$.actual.points[0].amount.amount")).isEqualTo("700.00");
        List<Object> projected = JsonPath.read(body(projection), "$.projected.points");
        assertThat(projected).isEmpty();
        assertThat(read(projection, "$.target.amount")).isEqualTo("12000.00");

        MvcTestResult list = mvc.get().uri(GOALS, workspace).with(ana()).exchange();
        assertThat((Integer) JsonPath.read(body(list), "$.summary.forecastCount")).isZero();
        assertThat(read(list, "$.summary.forecastFreshness")).isNotNull();
    }

    // --- helpers ---

    private String createLisbon() throws Exception {
        String deadline = START.plusMonths(9).toString();
        MvcTestResult result = mvc.post().uri(GOALS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Viagem para Lisboa", "type": "PURCHASE",
                         "targetAmount": {"amount": "12000.00", "currency": "BRL"},
                         "plannedMonthly": {"amount": "700.00", "currency": "BRL"}, "deadline": "%s",
                         "destinationAccountId": "%s", "sourceAccountId": "%s"}
                        """.formatted(deadline, trip, aurora)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id");
    }

    private MvcTestResult contribute(String goal, String amount) {
        return mvc.post().uri(GOAL + "/contributions", workspace, goal).with(ana())
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                        {"sourceAccountId": "%s", "amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "%s"}
                        """.formatted(aurora, amount, TODAY)).exchange();
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
        assertThat(mvc.post().uri(WORKSPACE + "/transactions", workspace).with(ana())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type": "TRANSFER", "accountId": "%s", "destinationAccountId": "%s",
                         "amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "%s", "description": "Transf."}
                        """.formatted(from, to, amount, on)).exchange()).hasStatus(HttpStatus.CREATED);
    }

    /** Tabelas que a simulação poderia tocar: nenhuma pode mudar. */
    private String snapshot() {
        return jdbc.queryForObject("select count(*) from outbox_events", Integer.class) + "|"
                + jdbc.queryForObject("select coalesce(string_agg(t::text, ',' order by t::text), '') from goals t",
                        String.class) + "|" + jdbc.queryForObject("select count(*) from transactions", Integer.class);
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
