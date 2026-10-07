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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@ExtendWith(OutputCaptureExtension.class)
class TransactionApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";

    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";

    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";

    private static final String TRANSACTION = TRANSACTIONS + "/{transactionId}";

    @Autowired
    JdbcTemplate jdbc;

    private String anasWorkspace;

    private String bobsWorkspace;

    private String aurora;

    private String norte;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        bobsWorkspace = read(mvc.post().uri(ME).with(bob()).exchange(), "$.workspaceId").toString();
        aurora = createAccount(ana(), anasWorkspace, "Banco Aurora");
        norte = createAccount(ana(), anasWorkspace, "Banco Norte");
    }

    // --- criação ---

    @Test
    void createsExpense() throws Exception {
        MvcTestResult result = create(ana(), anasWorkspace, "k-1", expense(aurora, "86.4", "  Bistrô Lume "));

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.workspaceId", v -> assertThat(v).asString().isEqualTo(anasWorkspace))
                .hasPathSatisfying("$.accountId", v -> assertThat(v).asString().isEqualTo(aurora))
                .hasPathSatisfying("$.type", v -> assertThat(v).asString().isEqualTo("EXPENSE"))
                .hasPathSatisfying("$.amount.amount", v -> assertThat(v).asString().isEqualTo("86.40"))
                .hasPathSatisfying("$.amount.currency", v -> assertThat(v).asString().isEqualTo("BRL"))
                .hasPathSatisfying("$.occurredOn", v -> assertThat(v).asString().isEqualTo("2026-09-22"))
                .hasPathSatisfying("$.description", v -> assertThat(v).asString().isEqualTo("Bistrô Lume"))
                .hasPathSatisfying("$.status", v -> assertThat(v).asString().isEqualTo("POSTED"))
                .hasPathSatisfying("$.source", v -> assertThat(v).asString().isEqualTo("MANUAL"))
                .hasPathSatisfying("$.createdAt", v -> assertThat(v).asString().endsWith("Z"));
        String id = idFrom(result);
        assertThat(UUID.fromString(id).version()).isEqualTo(7);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/workspaces/" + anasWorkspace + "/transactions/" + id);
        assertThat(read(result, "$.destinationAccountId")).isNull();
        assertThat(jdbc.queryForObject("select amount::text from transactions", String.class)).isEqualTo("86.4000");
    }

    /** O valor monetário trafega como string: número JSON seria lido como ponto flutuante por muitos clientes. */
    @Test
    void amountIsAlwaysAStringInResponses() throws Exception {
        MvcTestResult result = create(ana(), anasWorkspace, "k-1", expense(aurora, "100", "Aluguel"));

        assertThat(read(result, "$.amount.amount")).isInstanceOf(String.class).isEqualTo("100.00");
    }

    @Test
    void createsTransferAndAdjustment() throws Exception {
        MvcTestResult transfer = create(ana(), anasWorkspace, "k-1", """
                {"type": "TRANSFER", "accountId": "%s", "destinationAccountId": "%s",
                 "amount": {"amount": "500.00", "currency": "BRL"}, "occurredOn": "2026-09-21",
                 "description": "Transferência para Reserva"}
                """.formatted(aurora, norte));
        MvcTestResult opening = create(ana(), anasWorkspace, "k-2", """
                {"type": "ADJUSTMENT", "adjustmentDirection": "INCREASE", "accountId": "%s",
                 "amount": {"amount": "1200.00", "currency": "BRL"}, "occurredOn": "2026-09-01",
                 "description": "Saldo inicial"}
                """.formatted(norte));

        assertThat(transfer).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.destinationAccountId", v -> assertThat(v).asString().isEqualTo(norte));
        assertThat(opening).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.adjustmentDirection", v -> assertThat(v).asString().isEqualTo("INCREASE"));
    }

    @Test
    void createsLinkedRefund() throws Exception {
        String purchase = idFrom(create(ana(), anasWorkspace, "k-1", expense(aurora, "100.00", "Loja")));

        MvcTestResult refund = create(ana(), anasWorkspace, "k-2", """
                {"type": "REFUND", "accountId": "%s", "refundOfTransactionId": "%s",
                 "amount": {"amount": "30.00", "currency": "BRL"}, "occurredOn": "2026-09-23", "description": "Estorno"}
                """.formatted(aurora, purchase));
        MvcTestResult excessive = create(ana(), anasWorkspace, "k-3", """
                {"type": "REFUND", "accountId": "%s", "refundOfTransactionId": "%s",
                 "amount": {"amount": "70.01", "currency": "BRL"}, "occurredOn": "2026-09-23", "description": "Estorno"}
                """.formatted(aurora, purchase));

        assertThat(refund).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.refundOfTransactionId", v -> assertThat(v).asString().isEqualTo(purchase));
        assertValidationFailed(excessive, "amount", "EXCEEDS_REFUNDABLE");
    }

    // --- idempotência ---

    @Test
    void retryWithSameKeyReplaysWithoutDuplicating() throws Exception {
        MvcTestResult first = create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume"));
        MvcTestResult retry = create(ana(), anasWorkspace, "k-1", expense(aurora, "86.4", "Bistrô Lume"));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.OK);
        assertThat(retry.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsUnprocessable() {
        create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume"));

        assertThat(create(ana(), anasWorkspace, "k-1", expense(aurora, "99.99", "Bistrô Lume")))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    void idempotencyKeyIsRequired() {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content(expense(aurora, "86.40", "Bistrô Lume")).exchange();

        assertValidationFailed(result, "Idempotency-Key", "REQUIRED");
        assertThat(countTransactions()).isZero();
    }

    // --- validação ---

    @Test
    void rejectsInvalidPayloads() {
        assertValidationFailed(create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "  ")), "description",
                null);
        assertValidationFailed(create(ana(), anasWorkspace, "k-2", expense(aurora, "8,64", "X")), "amount",
                "INVALID_FORMAT");
        assertValidationFailed(create(ana(), anasWorkspace, "k-3", expense(aurora, "0", "X")), "amount",
                "NOT_POSITIVE");
        assertValidationFailed(create(ana(), anasWorkspace, "k-4", expense(aurora, "1.001", "X")), "amount",
                "TOO_MANY_DECIMALS");
        assertValidationFailed(create(ana(), anasWorkspace, "k-5", """
                {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "1", "currency": "USD"},
                 "occurredOn": "2026-09-22", "description": "X"}
                """.formatted(aurora)), "currency", "MISMATCH");
        assertValidationFailed(create(ana(), anasWorkspace, "k-6", """
                {"type": "EXPENSE", "accountId": "%s", "occurredOn": "2026-09-22", "description": "X"}
                """.formatted(aurora)), "amount", null);
        assertValidationFailed(create(ana(), anasWorkspace, "k-7", """
                {"type": "PAYMENT", "accountId": "%s", "amount": {"amount": "1", "currency": "BRL"},
                 "occurredOn": "2026-09-22", "description": "X"}
                """.formatted(aurora)), "type", "INVALID");
        assertThat(countTransactions()).isZero();
    }

    @Test
    void malformedDateIsBadRequest() {
        assertThat(create(ana(), anasWorkspace, "k-1", """
                {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "1", "currency": "BRL"},
                 "occurredOn": "22/09/2026", "description": "X"}
                """.formatted(aurora))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri(TRANSACTIONS, anasWorkspace).param("from", "ontem").with(ana()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void archivedAccountRejectsNewTransactions() {
        assertThat(mvc.post().uri(ACCOUNTS + "/{id}/archive", anasWorkspace, aurora).with(ana()))
                .hasStatus(HttpStatus.OK);

        assertThat(create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume")))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("ACCOUNT_ARCHIVED"));
    }

    // --- consulta ---

    @Test
    void listsWithFiltersAndPagination() throws Exception {
        create(ana(), anasWorkspace, "k-1", expense(aurora, "24.50", "Café Grão", "2026-09-19"));
        create(ana(), anasWorkspace, "k-2", expense(aurora, "86.40", "Bistrô Lume", "2026-09-22"));
        create(ana(), anasWorkspace, "k-3", expense(norte, "296.08", "Mercado Bom Preço", "2026-09-22"));

        assertThat(mvc.get().uri(TRANSACTIONS, anasWorkspace).param("pageSize", "2").with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(2))
                .hasPathSatisfying("$.items[0].description", d -> assertThat(d).asString().isEqualTo("Mercado Bom Preço"))
                .hasPathSatisfying("$.page", p -> assertThat(p).asNumber().isEqualTo(0))
                .hasPathSatisfying("$.pageSize", p -> assertThat(p).asNumber().isEqualTo(2))
                .hasPathSatisfying("$.totalItems", t -> assertThat(t).asNumber().isEqualTo(3));

        assertThat(mvc.get().uri(TRANSACTIONS, anasWorkspace).param("accountId", aurora).param("from", "2026-09-20")
                .param("q", "lume").param("type", "EXPENSE").param("status", "POSTED").with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.items[0].description", d -> assertThat(d).asString().isEqualTo("Bistrô Lume"));
    }

    @Test
    void rejectsPageSizeAboveLimit() {
        assertValidationFailed(mvc.get().uri(TRANSACTIONS, anasWorkspace).param("pageSize", "101").with(ana())
                .exchange(), "pageSize", "OUT_OF_RANGE");
    }

    @Test
    void emptyWorkspaceListsNothing() {
        assertThat(mvc.get().uri(TRANSACTIONS, anasWorkspace).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(0))
                .hasPathSatisfying("$.totalItems", t -> assertThat(t).asNumber().isEqualTo(0));
    }

    @Test
    void getsTransaction() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume")));

        assertThat(mvc.get().uri(TRANSACTION, anasWorkspace, id).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.id", v -> assertThat(v).asString().isEqualTo(id))
                .hasPathSatisfying("$.amount.amount", v -> assertThat(v).asString().isEqualTo("86.40"));
    }

    // --- edição e ciclo de vida ---

    @Test
    void updatesDescription() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "BISTRO LUME PIRACICABA")));

        assertThat(mvc.put().uri(TRANSACTION, anasWorkspace, id).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description": "Bistrô Lume"}
                        """)).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.description", v -> assertThat(v).asString().isEqualTo("Bistrô Lume"))
                .hasPathSatisfying("$.amount.amount", v -> assertThat(v).asString().isEqualTo("86.40"));
    }

    @Test
    void statusLifecycle() throws Exception {
        String pending = idFrom(create(ana(), anasWorkspace, "k-1", """
                {"type": "EXPENSE", "status": "PENDING", "accountId": "%s",
                 "amount": {"amount": "39.90", "currency": "BRL"}, "occurredOn": "2026-09-20", "description": "Streamo"}
                """.formatted(aurora)));

        assertStatus(mvc.post().uri(TRANSACTION + "/post", anasWorkspace, pending).with(ana()).exchange(), "POSTED");
        assertThat(mvc.post().uri(TRANSACTION + "/cancel", anasWorkspace, pending).with(ana()))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("TRANSACTION_STATUS_CONFLICT"));
        assertStatus(mvc.post().uri(TRANSACTION + "/reverse", anasWorkspace, pending).with(ana()).exchange(),
                "REVERSED");
        assertStatus(mvc.post().uri(TRANSACTION + "/reverse", anasWorkspace, pending).with(ana()).exchange(),
                "REVERSED");

        assertThat(jdbc.queryForObject("select status from transactions", String.class)).isEqualTo("REVERSED");
    }

    /** Não há exclusão física: fato financeiro é cancelado ou estornado. */
    @Test
    void transactionsCannotBeDeleted() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume")));

        assertThat(mvc.delete().uri(TRANSACTION, anasWorkspace, id).with(ana()))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(countTransactions()).isEqualTo(1);
    }

    // --- isolamento ---

    @Test
    void userCannotTouchTransactionsOfAnotherWorkspace() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume")));

        assertWorkspaceNotFound(mvc.get().uri(TRANSACTIONS, anasWorkspace).with(bob()).exchange());
        assertWorkspaceNotFound(create(bob(), anasWorkspace, "k-2", expense(aurora, "1", "Invasora")));
        assertWorkspaceNotFound(mvc.get().uri(TRANSACTION, anasWorkspace, id).with(bob()).exchange());
        assertWorkspaceNotFound(mvc.put().uri(TRANSACTION, anasWorkspace, id).with(bob())
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\": \"Invasora\"}").exchange());
        assertWorkspaceNotFound(mvc.post().uri(TRANSACTION + "/reverse", anasWorkspace, id).with(bob()).exchange());

        assertThat(countTransactions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select description from transactions", String.class)).isEqualTo("Bistrô Lume");
        assertThat(jdbc.queryForObject("select status from transactions", String.class)).isEqualTo("POSTED");
    }

    /** Bob, no próprio Workspace, não consegue lançar na conta da Ana. */
    @Test
    void foreignAccountInBodyIsNotFound() {
        assertThat(create(bob(), bobsWorkspace, "k-1", expense(aurora, "1", "Na conta da Ana")))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThat(countTransactions()).isZero();
    }

    @Test
    void foreignAndUnknownTransactionsAreIndistinguishable() throws Exception {
        String anas = idFrom(create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume")));

        MvcTestResult foreign = mvc.get().uri(TRANSACTION, bobsWorkspace, anas).with(bob()).exchange();
        MvcTestResult unknown = mvc.get().uri(TRANSACTION, bobsWorkspace, UUID.randomUUID()).with(bob()).exchange();

        assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("TRANSACTION_NOT_FOUND"));
        assertThat(withoutTraceId(foreign)).isEqualTo(withoutTraceId(unknown));
    }

    @Test
    void listNeverMixesWorkspaces() throws Exception {
        create(ana(), anasWorkspace, "k-1", expense(aurora, "86.40", "Bistrô Lume"));
        String bobsAccount = createAccount(bob(), bobsWorkspace, "Conta do Bob");
        String bobs = idFrom(create(bob(), bobsWorkspace, "k-1", expense(bobsAccount, "10", "Do Bob")));

        assertThat(mvc.get().uri(TRANSACTIONS, bobsWorkspace).with(bob())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.items[0].id", i -> assertThat(i).asString().isEqualTo(bobs));
    }

    // --- segurança e privacidade ---

    @Test
    void requiresAuthentication() {
        assertThat(mvc.get().uri(TRANSACTIONS, anasWorkspace)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri(TRANSACTIONS, anasWorkspace).header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(expense(aurora, "1", "X")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedTransactionIdIsBadRequest() {
        assertThat(mvc.get().uri(TRANSACTIONS + "/not-a-uuid", anasWorkspace).with(ana()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("MALFORMED_REQUEST"));
    }

    /** Valor e descrição são dados financeiros: logs carregam apenas IDs. */
    @Test
    void logsOnlyIds(CapturedOutput output) {
        assertThat(create(ana(), anasWorkspace, "k-1", expense(aurora, "4321.98", "Clínica Sigilosa")))
                .hasStatus(HttpStatus.CREATED);

        assertThat(output).contains("Transaction created")
                .doesNotContain("Clínica Sigilosa")
                .doesNotContain("4321.98");
    }

    // --- helpers ---

    private MvcTestResult create(RequestPostProcessor who, String workspaceId, String key, String json) {
        return mvc.post().uri(TRANSACTIONS, workspaceId).with(who).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private String createAccount(RequestPostProcessor who, String workspaceId, String name) throws Exception {
        return idFrom(mvc.post().uri(ACCOUNTS, workspaceId).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "type": "CHECKING"}
                        """.formatted(name)).exchange());
    }

    private static String expense(String accountId, String amount, String description) {
        return expense(accountId, amount, description, "2026-09-22");
    }

    private static String expense(String accountId, String amount, String description, String occurredOn) {
        return """
                {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                 "occurredOn": "%s", "description": "%s"}
                """.formatted(accountId, amount, occurredOn, description);
    }

    private static void assertStatus(MvcTestResult result, String status) {
        assertThat(result).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.status", s -> assertThat(s).asString().isEqualTo(status));
    }

    private static void assertValidationFailed(MvcTestResult result, String field, String reason) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().startsWith(field));
        if (reason != null) {
            assertThat(result).bodyJson()
                    .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo(reason));
        }
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

    private static String idFrom(MvcTestResult result) throws Exception {
        return read(result, "$.id").toString();
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private static String withoutTraceId(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\"traceId\":\"[^\"]*\"", "");
    }

    private int countTransactions() {
        return jdbc.queryForObject("select count(*) from transactions", Integer.class);
    }
}
