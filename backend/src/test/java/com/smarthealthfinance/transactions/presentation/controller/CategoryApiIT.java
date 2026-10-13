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
import java.util.List;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Catálogo, categorização e filtro por categoria pela API (ADR-0012 §6–§9). */
@ExtendWith(OutputCaptureExtension.class)
class CategoryApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";

    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";

    private static final String CATEGORIES = "/api/v1/workspaces/{workspaceId}/categories";

    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";

    private static final String CATEGORY = TRANSACTIONS + "/{transactionId}/category";

    @Autowired
    JdbcTemplate jdbc;

    private String anasWorkspace;

    private String bobsWorkspace;

    private String aurora;

    private String norte;

    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        bobsWorkspace = read(mvc.post().uri(ME).with(bob()).exchange(), "$.workspaceId").toString();
        aurora = createAccount(anasWorkspace, "Banco Aurora");
        norte = createAccount(anasWorkspace, "Banco Norte");
    }

    // --- catálogo ---

    @Test
    void listsTheCatalog() throws Exception {
        MvcTestResult result = mvc.get().uri(CATEGORIES, anasWorkspace).with(ana()).exchange();

        assertThat(result).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", n -> assertThat(n).asNumber().isEqualTo(28))
                .hasPathSatisfying("$.items[0].code", c -> assertThat(c).asString().isEqualTo("HOUSING"))
                .hasPathSatisfying("$.items[0].name", c -> assertThat(c).asString().isEqualTo("Moradia"))
                .hasPathSatisfying("$.items[0].nature", c -> assertThat(c).asString().isEqualTo("EXPENSE"))
                .hasPathSatisfying("$.items[0].active", c -> assertThat(c).isEqualTo(true))
                .hasPathSatisfying("$.items[22].code", c -> assertThat(c).asString().isEqualTo("SALARY"))
                .hasPathSatisfying("$.items[22].nature", c -> assertThat(c).asString().isEqualTo("INCOME"));
    }

    @Test
    void catalogRequiresMembership() {
        assertWorkspaceNotFound(mvc.get().uri(CATEGORIES, anasWorkspace).with(bob()).exchange());
        assertThat(mvc.get().uri(CATEGORIES, anasWorkspace).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // --- categorização ---

    @Test
    void categorizesChangesAndClears() throws Exception {
        String lume = createExpense("86.40", "Loja Alfa");
        assertThat(read(get(lume), "$.categoryCode")).isNull();

        assertThat(categorize(lume, "\"DINING_OUT\"")).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.categoryCode", c -> assertThat(c).asString().isEqualTo("DINING_OUT"))
                .hasPathSatisfying("$.amount.amount", a -> assertThat(a).asString().isEqualTo("86.40"));
        assertThat(read(get(lume), "$.categoryCode")).isEqualTo("DINING_OUT");

        assertThat(read(categorize(lume, "\"GROCERIES\""), "$.categoryCode")).isEqualTo("GROCERIES");
        assertThat(read(categorize(lume, "null"), "$.categoryCode")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from transactions where category_code is not null",
                Integer.class)).isZero();
    }

    @Test
    void categorizesIncomeAndRefund() throws Exception {
        String salary = create("""
                {"type": "INCOME", "accountId": "%s", "amount": {"amount": "6500.00", "currency": "BRL"},
                 "occurredOn": "2026-09-01", "description": "Salário"}
                """.formatted(aurora));
        String purchase = createExpense("100.00", "Loja");
        String devolution = create("""
                {"type": "REFUND", "accountId": "%s", "refundOfTransactionId": "%s",
                 "amount": {"amount": "30.00", "currency": "BRL"}, "occurredOn": "2026-09-23", "description": "Estorno"}
                """.formatted(aurora, purchase));

        assertThat(categorize(salary, "\"SALARY\"")).hasStatus(HttpStatus.OK);
        assertThat(categorize(devolution, "\"SHOPPING\"")).hasStatus(HttpStatus.OK);
    }

    @Test
    void rejectsInvalidCategories() throws Exception {
        String lume = createExpense("86.40", "Loja Alfa");
        String move = create("""
                {"type": "TRANSFER", "accountId": "%s", "destinationAccountId": "%s",
                 "amount": {"amount": "500.00", "currency": "BRL"}, "occurredOn": "2026-09-21",
                 "description": "Transferência para Reserva"}
                """.formatted(aurora, norte));

        assertValidationFailed(categorize(lume, "\"UNKNOWN\""), "categoryCode", "INVALID");
        assertValidationFailed(categorize(lume, "\"dining out\""), "categoryCode", "INVALID");
        assertValidationFailed(categorize(lume, "\"SALARY\""), "categoryCode", "INCOMPATIBLE_WITH_TYPE");
        assertValidationFailed(categorize(move, "\"DINING_OUT\""), "categoryCode", "NOT_ALLOWED");
        assertValidationFailed(categorize(lume, "\"" + "A".repeat(41) + "\""), "categoryCode", null);
        assertThat(jdbc.queryForObject("select count(*) from transactions where category_code is not null",
                Integer.class)).isZero();
    }

    /** Transação alheia é indistinguível de inexistente (regra do 404). */
    @Test
    void cannotCategorizeAnotherWorkspacesTransaction() throws Exception {
        String lume = createExpense("86.40", "Loja Alfa");

        assertThat(mvc.put().uri(CATEGORY, bobsWorkspace, lume).with(bob()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryCode\": \"DINING_OUT\"}").exchange())
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("TRANSACTION_NOT_FOUND"));
        assertWorkspaceNotFound(mvc.put().uri(CATEGORY, anasWorkspace, lume).with(bob())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryCode\": \"DINING_OUT\"}").exchange());
        assertThat(read(get(lume), "$.categoryCode")).isNull();
    }

    // --- filtro ---

    @Test
    void filtersTheListByCategory() throws Exception {
        String lume = createExpense("86.40", "Loja Alfa");
        createExpense("296.08", "Loja Beta");
        categorize(lume, "\"DINING_OUT\"");

        assertThat(descriptions(list("categoryCode=DINING_OUT"))).containsExactly("Loja Alfa");
        assertThat(descriptions(list("uncategorized=true"))).containsExactly("Loja Beta");
        assertThat(descriptions(list("categoryCode=TRAVEL"))).isEmpty();
        assertValidationFailed(list("categoryCode=DINING_OUT&uncategorized=true"), "uncategorized",
                "CONFLICTS_WITH_CATEGORY");
        assertValidationFailed(list("categoryCode=dining"), "categoryCode", "INVALID");
    }

    /** A categoria revela hábitos de consumo: o log carrega só os IDs. */
    @Test
    void logsOnlyIds(CapturedOutput output) throws Exception {
        String lume = createExpense("86.40", "Loja Alfa");

        assertThat(categorize(lume, "\"DINING_OUT\"")).hasStatus(HttpStatus.OK);

        assertThat(output).contains("Transaction categorized")
                .doesNotContain("DINING_OUT")
                .doesNotContain("Alimentação fora")
                .doesNotContain("Loja Alfa");
    }

    // --- categorização automática e comerciantes (ADR-0013) ---

    @Test
    void manualEntriesAreCategorizedByRulesAndExposeSourceAndMerchant() throws Exception {
        String market = createExpense("296.08", "MERCADO BOM PREÇO 0712");
        String unknown = createExpense("10.00", "Pix enviado - Maria Exemplo");

        assertThat(get(market)).bodyJson()
                .hasPathSatisfying("$.categoryCode", c -> assertThat(c).asString().isEqualTo("GROCERIES"))
                .hasPathSatisfying("$.categorySource", c -> assertThat(c).asString().isEqualTo("RULE_SYSTEM"))
                .hasPathSatisfying("$.merchantName", c -> assertThat(c).asString().isEqualTo("Mercado Bom Preço"));
        assertThat(get(unknown)).bodyJson()
                .hasPathSatisfying("$.categoryCode", c -> assertThat(c).isNull())
                .hasPathSatisfying("$.categorySource", c -> assertThat(c).isNull())
                .hasPathSatisfying("$.merchantName", c -> assertThat(c).asString().isEqualTo("Maria Exemplo"));
        assertThat(jdbc.queryForObject("select category_rule_version from transactions where id = ?::uuid",
                String.class, market)).isEqualTo("system-v1");
    }

    @Test
    void userChoiceIsManualAndLaterRulesNeverOverwriteIt() throws Exception {
        String market = createExpense("296.08", "Mercado Bom Preço");

        assertThat(categorize(market, "\"HOME\"")).bodyJson()
                .hasPathSatisfying("$.categorySource", c -> assertThat(c).asString().isEqualTo("MANUAL"));
        createExpense("10.00", "Mercado Bom Preço");
        assertThat(categorizePending()).hasStatus(HttpStatus.OK);

        assertThat(read(get(market), "$.categoryCode")).isEqualTo("HOME");
        assertThat(read(get(market), "$.categorySource")).isEqualTo("MANUAL");
    }

    @Test
    void applyToSimilarLearnsTheRuleForTheMerchant() throws Exception {
        String first = createExpense("20.00", "Loja Alfa 01/09");
        String second = createExpense("30.00", "Loja Alfa 05/09");
        String manual = createExpense("40.00", "Loja Alfa 09/09");
        categorize(manual, "\"SHOPPING\"");
        String other = createExpense("50.00", "Loja Beta");

        assertThat(mvc.put().uri(CATEGORY, anasWorkspace, first).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryCode\": \"GIFTS_DONATIONS\", \"applyToSimilar\": true}").exchange())
                .hasStatus(HttpStatus.OK);

        assertThat(read(get(second), "$.categoryCode")).isEqualTo("GIFTS_DONATIONS");
        assertThat(read(get(second), "$.categorySource")).isEqualTo("RULE_LEARNED");
        assertThat(read(get(manual), "$.categoryCode")).isEqualTo("SHOPPING");
        assertThat(read(get(other), "$.categoryCode")).isNull();

        // O aprendizado vale para os próximos lançamentos do comerciante.
        String next = createExpense("60.00", "LOJA ALFA 22/09");
        assertThat(read(get(next), "$.categoryCode")).isEqualTo("GIFTS_DONATIONS");
        assertThat(read(get(next), "$.categorySource")).isEqualTo("RULE_LEARNED");
        assertThat(jdbc.queryForObject("select count(*) from category_rules", Integer.class)).isEqualTo(1);
    }

    @Test
    void applyToSimilarNeedsACategory() throws Exception {
        String lume = createExpense("86.40", "Loja Alfa");

        assertValidationFailed(mvc.put().uri(CATEGORY, anasWorkspace, lume).with(ana())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryCode\": null, \"applyToSimilar\": true}").exchange(),
                "applyToSimilar", "REQUIRES_CATEGORY");
    }

    @Test
    void categorizePendingTreatsOnlyUntouchedTransactionsOfTheWorkspace() throws Exception {
        String market = createExpense("296.08", "Mercado Bom Preço");
        String chosen = createExpense("10.00", "Mercado Sol");
        categorize(chosen, "\"DINING_OUT\"");
        // Transação anterior ao ADR-0013: sem comerciante nem origem.
        jdbc.update("""
                update transactions set category_code = null, category_nature = null, category_source = null,
                  category_rule_version = null, merchant_key = null, merchant_name = null,
                  merchant_normalizer_version = null where id = ?::uuid""", market);

        assertThat(categorizePending()).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.categorized", n -> assertThat(n).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.merchantsIdentified", n -> assertThat(n).asNumber().isEqualTo(1));
        assertThat(read(get(market), "$.categoryCode")).isEqualTo("GROCERIES");
        assertThat(read(get(chosen), "$.categoryCode")).isEqualTo("DINING_OUT");

        assertThat(categorizePending()).bodyJson()
                .hasPathSatisfying("$.categorized", n -> assertThat(n).asNumber().isEqualTo(0));
        assertWorkspaceNotFound(mvc.post().uri(TRANSACTIONS + "/categorize-pending", anasWorkspace).with(bob())
                .exchange());
    }

    // --- helpers ---

    private MvcTestResult categorize(String transactionId, String codeJson) {
        return mvc.put().uri(CATEGORY, anasWorkspace, transactionId).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryCode\": " + codeJson + "}").exchange();
    }

    private MvcTestResult categorizePending() {
        return mvc.post().uri(TRANSACTIONS + "/categorize-pending", anasWorkspace).with(ana()).exchange();
    }

    private MvcTestResult get(String transactionId) {
        return mvc.get().uri(TRANSACTIONS + "/{transactionId}", anasWorkspace, transactionId).with(ana()).exchange();
    }

    private MvcTestResult list(String query) {
        return mvc.get().uri(TRANSACTIONS + "?" + query, anasWorkspace).with(ana()).exchange();
    }

    private String createExpense(String amount, String description) throws Exception {
        return create("""
                {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                 "occurredOn": "2026-09-22", "description": "%s"}
                """.formatted(aurora, amount, description));
    }

    private String create(String json) throws Exception {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, anasWorkspace).with(ana())
                .header("Idempotency-Key", "k-" + ++keys).contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private String createAccount(String workspaceId, String name) throws Exception {
        return read(mvc.post().uri(ACCOUNTS, workspaceId).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "type": "CHECKING"}
                        """.formatted(name)).exchange(), "$.id").toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> descriptions(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.OK);
        return (List<String>) read(result, "$.items[*].description");
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

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
