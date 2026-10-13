package com.smarthealthfinance.analytics.presentation.controller;

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

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * API de Analytics ponta a ponta (ADR-0015): comandos na API → eventos → projeção → consultas. As datas são
 * relativas a "hoje" em São Paulo: dia 1 deste mês e do anterior, para o recorte de dias sempre incluí-las.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String ANALYTICS = "/api/v1/workspaces/{workspaceId}/analytics";

    @Autowired
    JdbcTemplate jdbc;

    private String workspace;
    private String account;
    private LocalDate thisMonth;
    private LocalDate lastMonth;
    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        mvc.post().uri(ME).with(bob()).exchange();
        account = read(mvc.post().uri(ACCOUNTS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}").exchange(), "$.id").toString();
        thisMonth = LocalDate.now(ZoneId.of("America/Sao_Paulo")).withDayOfMonth(1);
        lastMonth = thisMonth.minusMonths(1);
    }

    private void seed() throws Exception {
        create("INCOME", "6500.00", lastMonth, "Salário - Empresa Fictícia Ltda");
        create("EXPENSE", "1800.00", lastMonth, "Aluguel - Imobiliária Exemplo");
        create("EXPENSE", "100.00", lastMonth, "Bistrô Lume");
        create("INCOME", "6500.00", thisMonth, "Salário - Empresa Fictícia Ltda");
        create("EXPENSE", "1800.00", thisMonth, "Aluguel - Imobiliária Exemplo");
        create("EXPENSE", "250.00", thisMonth, "Bistrô Lume");
        create("EXPENSE", "35.00", thisMonth, "Loja Sigilosa");
        IdentityTables.awaitQuiescence(jdbc);
    }

    @Test
    void overviewComputesTotalsVariationsCompositionAndChanges() throws Exception {
        seed();

        MvcTestResult result = get("/overview", "");
        assertThat(result).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.state", s -> assertThat(s).asString().isEqualTo("READY"))
                .hasPathSatisfying("$.period.month", m -> assertThat(m).asString()
                        .isEqualTo(YearMonth.from(thisMonth).toString()))
                .hasPathSatisfying("$.freshness.state", s -> assertThat(s).asString().isEqualTo("UP_TO_DATE"))
                .hasPathSatisfying("$.income.amount.amount", a -> assertThat(a).asString().isEqualTo("6500.00"))
                .hasPathSatisfying("$.income.vsPrevious.trend", t -> assertThat(t).asString().isEqualTo("STABLE"))
                .hasPathSatisfying("$.expense.amount.amount", a -> assertThat(a).asString().isEqualTo("2085.00"))
                .hasPathSatisfying("$.expense.vsPrevious.base.amount", a -> assertThat(a).asString().isEqualTo("1900.00"))
                .hasPathSatisfying("$.expense.vsPrevious.difference.amount",
                        a -> assertThat(a).asString().isEqualTo("185.00"))
                .hasPathSatisfying("$.expense.vsPrevious.percent", p -> assertThat(p).asString().isEqualTo("9.7"))
                .hasPathSatisfying("$.expense.vsPrevious.trend", t -> assertThat(t).asString().isEqualTo("UP"))
                .hasPathSatisfying("$.net.amount.amount", a -> assertThat(a).asString().isEqualTo("4415.00"))
                .hasPathSatisfying("$.savedShare", s -> assertThat(s).asString().isEqualTo("67.9"))
                .hasPathSatisfying("$.months.length()", n -> assertThat(n).asNumber().isEqualTo(6))
                .hasPathSatisfying("$.composition.net.amount", a -> assertThat(a).asString().isEqualTo("4415.00"))
                .hasPathSatisfying("$.composition.outflows[0].categoryCode",
                        c -> assertThat(c).asString().isEqualTo("HOUSING"))
                .hasPathSatisfying("$.changes.netDelta.amount", a -> assertThat(a).asString().isEqualTo("-185.00"))
                .hasPathSatisfying("$.changes.items[0].kind", k -> assertThat(k).asString().isEqualTo("EXPENSE_CATEGORY"))
                .hasPathSatisfying("$.changes.items[0].categoryCode",
                        c -> assertThat(c).asString().isEqualTo("DINING_OUT"))
                .hasPathSatisfying("$.changes.items[0].impact.amount", a -> assertThat(a).asString().isEqualTo("-150.00"))
                .hasPathSatisfying("$.comparison.average.monthsUsed", n -> assertThat(n).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.comparison.average.state", s -> assertThat(s).asString().isEqualTo("LIMITED"));
    }

    @Test
    void everyTabAnswers() throws Exception {
        seed();

        assertThat(get("/spending", "")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.total.amount.amount", a -> assertThat(a).asString().isEqualTo("2085.00"))
                .hasPathSatisfying("$.largestExpenses[0].amount.amount", a -> assertThat(a).asString().isEqualTo("1800.00"))
                .hasPathSatisfying("$.categories[0].share", s -> assertThat(s).asString().isEqualTo("86.3"));
        assertThat(get("/income", "")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.sources[0].merchantName", n -> assertThat(n).asString().isEqualTo("Empresa Fictícia"))
                .hasPathSatisfying("$.sources[0].recurrence", r -> assertThat(r).asString().isEqualTo("OCCASIONAL"));
        assertThat(get("/categories", "")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.totalExpense.amount", a -> assertThat(a).asString().isEqualTo("2085.00"))
                .hasPathSatisfying("$.categories.length()", n -> assertThat(n).asNumber().isEqualTo(3));
        assertThat(get("/categories/DINING_OUT", "")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.amount.amount.amount", a -> assertThat(a).asString().isEqualTo("250.00"))
                .hasPathSatisfying("$.averageTicket.amount", a -> assertThat(a).asString().isEqualTo("250.00"))
                .hasPathSatisfying("$.merchants[0].merchantName", n -> assertThat(n).asString().isEqualTo("Bistrô Lume"));
        assertThat(get("/merchants", "?sort=COUNT")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.sort", s -> assertThat(s).asString().isEqualTo("COUNT"))
                .hasPathSatisfying("$.merchants.length()", n -> assertThat(n).asNumber().isEqualTo(3));
        assertThat(get("/evolution", "?months=3")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.months.length()", n -> assertThat(n).asNumber().isEqualTo(3))
                .hasPathSatisfying("$.months[1].expense.amount", a -> assertThat(a).asString().isEqualTo("1900.00"));
        assertThat(get("/overview", "?compareWith=AVERAGE&averageMonths=3&month=" + YearMonth.from(lastMonth)))
                .hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.period.partial", p -> assertThat(p).isEqualTo(false))
                .hasPathSatisfying("$.comparison.average.state",
                        s -> assertThat(s).asString().isEqualTo("INSUFFICIENT_DATA"))
                .hasPathSatisfying("$.changes", c -> assertThat(c).isNull());
    }

    @Test
    void emptyWorkspaceHasNoData() {
        assertThat(get("/overview", "")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.state", s -> assertThat(s).asString().isEqualTo("NO_DATA"))
                .hasPathSatisfying("$.income", i -> assertThat(i).isNull())
                .hasPathSatisfying("$.comparison.average.state",
                        s -> assertThat(s).asString().isEqualTo("INSUFFICIENT_DATA"));
    }

    @Test
    void rejectsInvalidParameters() {
        assertValidation(get("/overview", "?month=" + YearMonth.from(thisMonth).plusMonths(1)), "month", "FUTURE");
        assertValidation(get("/overview", "?month=2026-13"), "month", "INVALID");
        assertValidation(get("/spending", "?compareWith=MEDIA"), "compareWith", "INVALID");
        assertValidation(get("/income", "?averageMonths=13"), "averageMonths", "OUT_OF_RANGE");
        assertValidation(get("/merchants", "?sort=NAME"), "sort", "INVALID");
        assertValidation(get("/evolution", "?months=0"), "months", "OUT_OF_RANGE");
        assertValidation(get("/categories/UNKNOWN", ""), "categoryCode", "INVALID");
    }

    @Test
    void nonMembersCannotReadEvenWithInvalidParameters() {
        for (String path : List.of("/overview?month=bad", "/spending", "/income", "/categories",
                "/categories/DINING_OUT", "/merchants", "/evolution")) {
            assertThat(mvc.get().uri(ANALYTICS + path, workspace).with(bob()).exchange())
                    .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
        }
        assertThat(mvc.get().uri(ANALYTICS + "/overview", workspace).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logsCarryNoFinancialData(CapturedOutput output) throws Exception {
        seed();

        assertThat(get("/merchants", "")).hasStatus(HttpStatus.OK);
        assertThat(get("/categories/DINING_OUT", "")).hasStatus(HttpStatus.OK);

        assertThat(output.getAll()).contains("Analytics served")
                // Valores numéricos não são checados aqui: "35.00" aparece em timestamps (":35.004"). Nomes e códigos bastam.
                .doesNotContain("Sigilosa").doesNotContain("Bistrô").doesNotContain("DINING_OUT");
    }

    // --- helpers ---

    private void create(String type, String amount, LocalDate date, String description) throws Exception {
        assertThat(mvc.post().uri(TRANSACTIONS, workspace).with(ana()).header("Idempotency-Key", "k-" + ++keys)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "%s", "description": "%s"}
                        """.formatted(type, account, amount, date, description)).exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult get(String path, String query) {
        return mvc.get().uri(ANALYTICS + path + query, workspace).with(ana()).exchange();
    }

    private static void assertValidation(MvcTestResult result, String field, String code) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo(field))
                .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo(code));
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

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
