package com.smarthealthfinance.transactions.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** {@code POST .../transactions/{id}/mark-as-refund} (adendo de 2026-10-14 aos ADR-0005 §6/§8 e ADR-0009 §9). */
@ExtendWith(OutputCaptureExtension.class)
class MarkAsRefundApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String TRANSACTION = TRANSACTIONS + "/{transactionId}";
    private static final String MARK = TRANSACTION + "/mark-as-refund";
    private static final String OVERVIEW = "/api/v1/workspaces/{workspaceId}/overview";

    @Autowired
    JdbcTemplate jdbc;

    private String anasWorkspace;
    private String aurora;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        aurora = idFrom(mvc.post().uri(ACCOUNTS, anasWorkspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Banco Aurora", "type": "CHECKING"}
                        """).exchange());
    }

    @Test
    void reversesTheIncomeAndCreatesTheRefund() throws Exception {
        String income = importedIncome("129.90", "Estorno compra online - Loja Azul");
        String purchase = create("EXPENSE", "129.90", "Loja Azul");

        MvcTestResult result = mark(income, """
                {"refundOfTransactionId": "%s"}
                """.formatted(purchase));

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.type", v -> assertThat(v).asString().isEqualTo("REFUND"))
                .hasPathSatisfying("$.status", v -> assertThat(v).asString().isEqualTo("POSTED"))
                .hasPathSatisfying("$.source", v -> assertThat(v).asString().isEqualTo("IMPORT"))
                .hasPathSatisfying("$.amount.amount", v -> assertThat(v).asString().isEqualTo("129.90"))
                .hasPathSatisfying("$.refundOfTransactionId", v -> assertThat(v).asString().isEqualTo(purchase))
                .hasPathSatisfying("$.reclassifiedFromTransactionId", v -> assertThat(v).asString().isEqualTo(income));
        String refund = idFrom(result);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/workspaces/" + anasWorkspace + "/transactions/" + refund);

        assertThat(mvc.get().uri(TRANSACTION, anasWorkspace, income).with(ana()).exchange())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.status", v -> assertThat(v).asString().isEqualTo("REVERSED"))
                .hasPathSatisfying("$.reclassifiedAsTransactionId", v -> assertThat(v).asString().isEqualTo(refund));
    }

    /** O saldo não muda; a entrada sai de receitas e vai para reembolsos (ADR-0006 §9). */
    @Test
    void overviewMovesTheInflowFromIncomeToRefunds() throws Exception {
        create("INCOME", "5000.00", "Salário");
        create("EXPENSE", "300.00", "Loja Azul");
        String income = importedIncome("129.90", "Estorno compra online - Loja Azul");
        MvcTestResult before = overview();

        mark(income, null);
        MvcTestResult after = overview();

        assertThat(read(before, "$.cashFlow.income.amount")).isEqualTo("5129.90");
        assertThat(read(before, "$.cashFlow.refunds.amount")).isEqualTo("0.00");
        assertThat(read(after, "$.cashFlow.income.amount")).isEqualTo("5000.00");
        assertThat(read(after, "$.cashFlow.refunds.amount")).isEqualTo("129.90");
        assertThat(read(after, "$.cashFlow.expense.amount")).isEqualTo("300.00");
        assertThat(read(after, "$.cashFlow.net.amount")).isEqualTo(read(before, "$.cashFlow.net.amount"));
        assertThat(read(after, "$.summary.totalBalance.amount")).isEqualTo(read(before, "$.summary.totalBalance.amount"));
    }

    @Test
    void repeatingReplaysTheSameRefund() throws Exception {
        String income = importedIncome("50.00", "Estorno");
        MvcTestResult first = mark(income, null);
        MvcTestResult again = mark(income, "{}");

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(again).hasStatus(HttpStatus.OK);
        assertThat(idFrom(again)).isEqualTo(idFrom(first));
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(2);
    }

    @Test
    void manualIncomeAndUnknownTransactionAreRejected() throws Exception {
        String manual = create("INCOME", "50.00", "Estorno lançado à mão");

        assertValidationFailed(mark(manual, null), "transactionId", "NOT_RECLASSIFIABLE");
        assertThat(mark(UUID.randomUUID().toString(), null)).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void manuallyReversedIncomeConflicts() throws Exception {
        String income = importedIncome("50.00", "Estorno");
        mvc.post().uri(TRANSACTION + "/reverse", anasWorkspace, income).with(ana()).exchange();

        assertThat(mark(income, null)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("TRANSACTION_STATUS_CONFLICT"));
    }

    /** O banco também garante um único substituto por receita, mesmo com bug na aplicação. */
    @Test
    void databaseAllowsASingleReplacementPerIncome() throws Exception {
        String income = importedIncome("50.00", "Estorno");
        mark(income, null);

        assertThatThrownBy(() -> jdbc.update("""
                insert into transactions (id, workspace_id, type, account_id, amount, currency, occurred_on,
                  description, status, source, reclassified_from_transaction_id, created_at, updated_at, version)
                values (?, ?::uuid, 'REFUND', ?::uuid, 50, 'BRL', date '2026-09-22', 'Estorno', 'POSTED', 'IMPORT',
                  ?::uuid, now(), now(), 0)
                """, UUID.randomUUID(), anasWorkspace, aurora, income))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update transactions set reclassified_as_transaction_id = id where id = ?::uuid",
                create("INCOME", "1.00", "Receita")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void logsCarryNoFinancialData(CapturedOutput output) throws Exception {
        mark(importedIncome("4321.09", "Estorno Loja Secreta"), null);

        assertThat(output.getOut()).contains("Transaction marked as refund")
                .doesNotContain("4321").doesNotContain("Loja Secreta");
    }

    /** Lançamento manual + troca da origem: o caminho da importação já é coberto pelos testes de Ingestion. */
    private String importedIncome(String amount, String description) throws Exception {
        String id = create("INCOME", amount, description);
        jdbc.update("update transactions set source = 'IMPORT' where id = ?::uuid", id);
        return id;
    }

    private String create(String type, String amount, String description) throws Exception {
        return idFrom(mvc.post().uri(TRANSACTIONS, anasWorkspace).with(ana())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "2026-09-22", "description": "%s"}
                        """.formatted(type, aurora, amount, description)).exchange());
    }

    private MvcTestResult mark(String transactionId, String json) {
        var request = mvc.post().uri(MARK, anasWorkspace, transactionId).with(ana());
        return json == null ? request.exchange()
                : request.contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult overview() {
        return mvc.get().uri(OVERVIEW + "?period=CUSTOM&from=2026-09-01&to=2026-09-30", anasWorkspace).with(ana())
                .exchange();
    }

    private static void assertValidationFailed(MvcTestResult result, String field, String reason) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo(field))
                .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo(reason));
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(token -> token.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Nome"));
    }

    private static String idFrom(MvcTestResult result) throws Exception {
        return read(result, "$.id").toString();
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
