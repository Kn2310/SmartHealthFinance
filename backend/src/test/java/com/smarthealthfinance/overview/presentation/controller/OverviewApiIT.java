package com.smarthealthfinance.overview.presentation.controller;

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
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@ExtendWith(OutputCaptureExtension.class)
class OverviewApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String OVERVIEW = "/api/v1/workspaces/{workspaceId}/overview";

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    @Autowired
    JdbcTemplate jdbc;

    private final AtomicInteger keys = new AtomicInteger();

    private String anasWorkspace;
    private String bobsWorkspace;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        bobsWorkspace = read(mvc.post().uri(ME).with(bob()).exchange(), "$.workspaceId").toString();
        today = LocalDate.now(BUSINESS_ZONE);
    }

    // --- empty states ---

    @Test
    void brandNewWorkspaceHasNoDataAndNoInventedZeros() throws Exception {
        MvcTestResult result = overview(ana(), anasWorkspace, "");

        assertThat(result).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.state", v -> assertThat(v).asString().isEqualTo("NO_ACCOUNTS"))
                .hasPathSatisfying("$.workspaceId", v -> assertThat(v).asString().isEqualTo(anasWorkspace))
                .hasPathSatisfying("$.summary.accountCount", v -> assertThat(v).isEqualTo(0));
        assertThat(read(result, "$.summary.totalBalance")).isNull();
        assertThat(read(result, "$.cashFlow")).isNull();
        assertThat((java.util.List<?>) read(result, "$.accounts")).isEmpty();
        assertThat((java.util.List<?>) read(result, "$.recentTransactions")).isEmpty();
    }

    @Test
    void accountsWithoutTransactionsListTheAccountsWithoutBalances() throws Exception {
        createAccount(ana(), anasWorkspace, "Banco Aurora");

        MvcTestResult result = overview(ana(), anasWorkspace, "");

        assertThat(result).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.state", v -> assertThat(v).asString().isEqualTo("NO_TRANSACTIONS"))
                .hasPathSatisfying("$.accounts[0].name", v -> assertThat(v).asString().isEqualTo("Banco Aurora"))
                .hasPathSatisfying("$.accounts[0].movementCount", v -> assertThat(v).isEqualTo(0));
        assertThat(read(result, "$.accounts[0].balance")).isNull();
        assertThat(read(result, "$.summary.totalBalance")).isNull();
        assertThat(read(result, "$.cashFlow")).isNull();
    }

    @Test
    void historyOutsideThePeriodReturnsRealZeros() throws Exception {
        String aurora = createAccount(ana(), anasWorkspace, "Banco Aurora");
        postTransaction(ana(), anasWorkspace, "INCOME", aurora, null, "1000.00", today.minusMonths(3), "Salário antigo");

        MvcTestResult result = overview(ana(), anasWorkspace, "");

        assertThat(result).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.state", v -> assertThat(v).asString().isEqualTo("NO_ACTIVITY_IN_PERIOD"))
                .hasPathSatisfying("$.summary.totalBalance.amount", v -> assertThat(v).asString().isEqualTo("1000.00"))
                .hasPathSatisfying("$.cashFlow.income.amount", v -> assertThat(v).asString().isEqualTo("0.00"))
                .hasPathSatisfying("$.cashFlow.net.amount", v -> assertThat(v).asString().isEqualTo("0.00"));
    }

    // --- contract ---

    @Test
    void consolidatedOverviewContract() throws Exception {
        String aurora = createAccount(ana(), anasWorkspace, "Banco Aurora");
        String norte = createAccount(ana(), anasWorkspace, "Banco Norte");
        postTransaction(ana(), anasWorkspace, "ADJUSTMENT", aurora, null, "1000.00", today, "Saldo inicial",
                "\"adjustmentDirection\": \"INCREASE\"");
        postTransaction(ana(), anasWorkspace, "INCOME", aurora, null, "6800.00", today, "Salário");
        String purchase = postTransaction(ana(), anasWorkspace, "EXPENSE", aurora, null, "86.40", today, "Bistrô Lume");
        postTransaction(ana(), anasWorkspace, "REFUND", aurora, null, "20.00", today, "Estorno parcial",
                "\"refundOfTransactionId\": \"" + purchase + "\"");
        postTransaction(ana(), anasWorkspace, "TRANSFER", aurora, norte, "500.00", today, "Para reserva");
        postTransaction(ana(), anasWorkspace, "EXPENSE", norte, null, "10.00", today, "Pendente", "\"status\": \"PENDING\"");

        MvcTestResult result = overview(ana(), anasWorkspace, "");

        assertThat(result).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.state", v -> assertThat(v).asString().isEqualTo("READY"))
                .hasPathSatisfying("$.period.type", v -> assertThat(v).asString().isEqualTo("CURRENT_MONTH"))
                .hasPathSatisfying("$.period.from", v -> assertThat(v).asString().isEqualTo(today.withDayOfMonth(1).toString()))
                .hasPathSatisfying("$.period.to", v -> assertThat(v).asString().isEqualTo(today.toString()))
                .hasPathSatisfying("$.summary.balanceAsOf", v -> assertThat(v).asString().isEqualTo(today.toString()))
                // 1000 + 6800 - 86.40 + 20 (aurora -500) ; norte +500 ; total 7733.60
                .hasPathSatisfying("$.summary.totalBalance.amount", v -> assertThat(v).asString().isEqualTo("7733.60"))
                .hasPathSatisfying("$.summary.totalBalance.currency", v -> assertThat(v).asString().isEqualTo("BRL"))
                .hasPathSatisfying("$.summary.accountCount", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.summary.pendingTransactions", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.cashFlow.income.amount", v -> assertThat(v).asString().isEqualTo("6800.00"))
                .hasPathSatisfying("$.cashFlow.expense.amount", v -> assertThat(v).asString().isEqualTo("86.40"))
                .hasPathSatisfying("$.cashFlow.refunds.amount", v -> assertThat(v).asString().isEqualTo("20.00"))
                .hasPathSatisfying("$.cashFlow.net.amount", v -> assertThat(v).asString().isEqualTo("6733.60"))
                .hasPathSatisfying("$.cashFlow.transfers.count", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.cashFlow.transfers.volume.amount", v -> assertThat(v).asString().isEqualTo("500.00"))
                .hasPathSatisfying("$.accounts[0].name", v -> assertThat(v).asString().isEqualTo("Banco Aurora"))
                .hasPathSatisfying("$.accounts[0].type", v -> assertThat(v).asString().isEqualTo("CHECKING"))
                .hasPathSatisfying("$.accounts[0].includedInTotal", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.accounts[0].balance.amount", v -> assertThat(v).asString().isEqualTo("7233.60"))
                .hasPathSatisfying("$.accounts[0].movementCount", v -> assertThat(v).isEqualTo(5))
                .hasPathSatisfying("$.accounts[1].balance.amount", v -> assertThat(v).asString().isEqualTo("500.00"));
        assertThat(((java.util.List<?>) read(result, "$.recentTransactions"))).hasSize(5);
        // valores monetários sempre como string
        assertThat(read(result, "$.cashFlow.net.amount")).isInstanceOf(String.class);
        assertThat(read(result, "$.summary.totalBalance.amount")).isInstanceOf(String.class);
    }

    @Test
    void recentTransactionsExposeWhatTheUiNeeds() throws Exception {
        String aurora = createAccount(ana(), anasWorkspace, "Banco Aurora");
        String norte = createAccount(ana(), anasWorkspace, "Banco Norte");
        postTransaction(ana(), anasWorkspace, "TRANSFER", aurora, norte, "500.00", today, "Para reserva");

        MvcTestResult result = overview(ana(), anasWorkspace, "recentLimit=1");

        assertThat(result).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.recentTransactions[0].type", v -> assertThat(v).asString().isEqualTo("TRANSFER"))
                .hasPathSatisfying("$.recentTransactions[0].flow", v -> assertThat(v).asString().isEqualTo("TRANSFER"))
                .hasPathSatisfying("$.recentTransactions[0].status", v -> assertThat(v).asString().isEqualTo("POSTED"))
                .hasPathSatisfying("$.recentTransactions[0].source", v -> assertThat(v).asString().isEqualTo("MANUAL"))
                .hasPathSatisfying("$.recentTransactions[0].description", v -> assertThat(v).asString().isEqualTo("Para reserva"))
                .hasPathSatisfying("$.recentTransactions[0].amount.amount", v -> assertThat(v).asString().isEqualTo("500.00"))
                .hasPathSatisfying("$.recentTransactions[0].occurredOn", v -> assertThat(v).asString().isEqualTo(today.toString()))
                .hasPathSatisfying("$.recentTransactions[0].account.id", v -> assertThat(v).asString().isEqualTo(aurora))
                .hasPathSatisfying("$.recentTransactions[0].account.name", v -> assertThat(v).asString().isEqualTo("Banco Aurora"))
                .hasPathSatisfying("$.recentTransactions[0].destinationAccount.name", v -> assertThat(v).asString().isEqualTo("Banco Norte"));
        assertThat((java.util.List<?>) read(result, "$.recentTransactions")).hasSize(1);
    }

    // --- periods ---

    @Test
    void previousMonthAndCustomRange() throws Exception {
        String aurora = createAccount(ana(), anasWorkspace, "Banco Aurora");
        LocalDate previousMonthDay = today.withDayOfMonth(1).minusDays(1);
        postTransaction(ana(), anasWorkspace, "INCOME", aurora, null, "2000.00", previousMonthDay, "Salário anterior");
        postTransaction(ana(), anasWorkspace, "EXPENSE", aurora, null, "300.00", today, "Despesa de hoje");

        MvcTestResult previous = overview(ana(), anasWorkspace, "period=PREVIOUS_MONTH");
        MvcTestResult custom = overview(ana(), anasWorkspace, "period=CUSTOM&from=" + previousMonthDay + "&to=" + previousMonthDay);

        assertThat(previous).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.period.type", v -> assertThat(v).asString().isEqualTo("PREVIOUS_MONTH"))
                .hasPathSatisfying("$.period.to", v -> assertThat(v).asString().isEqualTo(previousMonthDay.toString()))
                .hasPathSatisfying("$.cashFlow.income.amount", v -> assertThat(v).asString().isEqualTo("2000.00"))
                .hasPathSatisfying("$.cashFlow.expense.amount", v -> assertThat(v).asString().isEqualTo("0.00"))
                .hasPathSatisfying("$.summary.totalBalance.amount", v -> assertThat(v).asString().isEqualTo("2000.00"));
        assertThat(custom).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.period.type", v -> assertThat(v).asString().isEqualTo("CUSTOM"))
                .hasPathSatisfying("$.cashFlow.income.amount", v -> assertThat(v).asString().isEqualTo("2000.00"));
    }

    @Test
    void invalidPeriodParametersAreValidationErrors() throws Exception {
        assertValidationFailed(overview(ana(), anasWorkspace, "period=YEAR"), "period", "INVALID");
        assertValidationFailed(overview(ana(), anasWorkspace, "period=CUSTOM"), "from", "REQUIRED");
        assertValidationFailed(overview(ana(), anasWorkspace, "period=CUSTOM&from=2026-09-10&to=2026-09-01"), "to",
                "BEFORE_FROM");
        assertValidationFailed(overview(ana(), anasWorkspace, "period=CUSTOM&from=2024-01-01&to=2026-01-01"), "to",
                "RANGE_TOO_LARGE");
        assertValidationFailed(overview(ana(), anasWorkspace, "from=2026-09-01"), "from", "NOT_ALLOWED");
        assertValidationFailed(overview(ana(), anasWorkspace, "recentLimit=0"), "recentLimit", "OUT_OF_RANGE");
        assertValidationFailed(overview(ana(), anasWorkspace, "recentLimit=21"), "recentLimit", "OUT_OF_RANGE");
        assertThat(overview(ana(), anasWorkspace, "period=CUSTOM&from=not-a-date&to=2026-09-01"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(overview(ana(), anasWorkspace, "recentLimit=abc")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // --- security / isolation ---

    @Test
    void requiresAuthentication() {
        assertThat(mvc.get().uri(OVERVIEW, anasWorkspace).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void foreignAndUnknownWorkspacesAreIndistinguishable() throws Exception {
        String aurora = createAccount(ana(), anasWorkspace, "Banco Aurora");
        postTransaction(ana(), anasWorkspace, "INCOME", aurora, null, "10.00", today, "Salário");

        MvcTestResult foreign = overview(bob(), anasWorkspace, "");
        MvcTestResult unknown = overview(bob(), UUID.randomUUID().toString(), "");

        assertWorkspaceNotFound(foreign);
        assertWorkspaceNotFound(unknown);
        assertThat(withoutTraceId(foreign)).isEqualTo(withoutTraceId(unknown));
        assertThat(overview(bob(), "not-a-uuid", "")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void authorizationWinsOverParameterValidation() throws Exception {
        assertWorkspaceNotFound(overview(bob(), anasWorkspace, "period=NOPE&recentLimit=999"));
    }

    @Test
    void eachWorkspaceGetsOnlyItsOwnOverview() throws Exception {
        String anasAccount = createAccount(ana(), anasWorkspace, "Conta da Ana");
        String bobsAccount = createAccount(bob(), bobsWorkspace, "Conta do Bob");
        postTransaction(ana(), anasWorkspace, "INCOME", anasAccount, null, "9000.00", today, "Salário da Ana");
        postTransaction(bob(), bobsWorkspace, "INCOME", bobsAccount, null, "42.00", today, "Pix do Bob");

        MvcTestResult anas = overview(ana(), anasWorkspace, "");
        MvcTestResult bobs = overview(bob(), bobsWorkspace, "");

        assertThat(anas).bodyJson()
                .hasPathSatisfying("$.summary.totalBalance.amount", v -> assertThat(v).asString().isEqualTo("9000.00"))
                .hasPathSatisfying("$.accounts[0].name", v -> assertThat(v).asString().isEqualTo("Conta da Ana"));
        assertThat(bobs).bodyJson()
                .hasPathSatisfying("$.summary.totalBalance.amount", v -> assertThat(v).asString().isEqualTo("42.00"))
                .hasPathSatisfying("$.cashFlow.income.amount", v -> assertThat(v).asString().isEqualTo("42.00"))
                .hasPathSatisfying("$.accounts[0].name", v -> assertThat(v).asString().isEqualTo("Conta do Bob"));
        assertThat(body(bobs)).doesNotContain("Salário da Ana", "Conta da Ana", anasAccount, anasWorkspace, "9000");
        assertThat(body(anas)).doesNotContain("Pix do Bob", "Conta do Bob", bobsAccount, bobsWorkspace);
    }

    @Test
    void onlyReadsAreAllowed() {
        assertThat(mvc.post().uri(OVERVIEW, anasWorkspace).with(ana()).contentType(MediaType.APPLICATION_JSON).content("{}")
                .exchange()).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void logsNeverCarryAmountsDescriptionsOrNames(CapturedOutput output) throws Exception {
        String aurora = createAccount(ana(), anasWorkspace, "Conta Sigilosa");
        postTransaction(ana(), anasWorkspace, "EXPENSE", aurora, null, "4321.98", today, "Clínica Sigilosa");

        assertThat(overview(ana(), anasWorkspace, "")).hasStatus(HttpStatus.OK);

        assertThat(output.getAll()).contains("Overview served").doesNotContain("4321", "Sigilosa");
    }

    // --- helpers ---

    private MvcTestResult overview(RequestPostProcessor who, String workspaceId, String query) {
        String uri = query.isEmpty() ? OVERVIEW : OVERVIEW + "?" + query;
        return mvc.get().uri(uri, workspaceId).with(who).exchange();
    }

    private String createAccount(RequestPostProcessor who, String workspaceId, String name) throws Exception {
        return read(mvc.post().uri(ACCOUNTS, workspaceId).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "type": "CHECKING"}
                        """.formatted(name)).exchange(), "$.id").toString();
    }

    private String postTransaction(RequestPostProcessor who, String workspaceId, String type, String accountId,
                                   String destinationId, String amount, LocalDate date, String description,
                                   String... extraFields) throws Exception {
        StringBuilder json = new StringBuilder("""
                {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                 "occurredOn": "%s", "description": "%s\"""".formatted(type, accountId, amount, date, description));
        if (destinationId != null) {
            json.append(", \"destinationAccountId\": \"").append(destinationId).append("\"");
        }
        for (String field : extraFields) {
            json.append(", ").append(field);
        }
        json.append("}");

        MvcTestResult result = mvc.post().uri(TRANSACTIONS, workspaceId).with(who)
                .header("Idempotency-Key", "k-" + keys.incrementAndGet())
                .contentType(MediaType.APPLICATION_JSON).content(json.toString()).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private static void assertValidationFailed(MvcTestResult result, String field, String reason) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().startsWith(field))
                .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo(reason));
    }

    private static void assertWorkspaceNotFound(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
    }

    private static RequestPostProcessor ana() {
        return identity("sub-ana", "ana@example.com");
    }

    private static RequestPostProcessor bob() {
        return identity("sub-bob", "bob@example.com");
    }

    private static RequestPostProcessor identity(String subject, String email) {
        return jwt().jwt(token -> token.subject(subject).claim("iss", ISSUER).claim("email", email).claim("name", "Nome"));
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String withoutTraceId(MvcTestResult result) throws Exception {
        return body(result).replaceAll("\"traceId\":\"[^\"]*\"", "");
    }
}
