package com.smarthealthfinance.transactions.application.usecase;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.analytics.application.usecase.RebuildAnalyticsProjection;
import com.smarthealthfinance.analytics.infrastructure.startup.AnalyticsRebuildRunner;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.infrastructure.startup.MerchantRenormalizationRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Renormalização v1 → v2 no banco: comerciantes, regras aprendidas, outbox e projeção (adendo ADR-0013 §1). */
@ExtendWith(OutputCaptureExtension.class)
class RenormalizeMerchantsIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RenormalizeMerchants renormalize;

    @Autowired
    RebuildAnalyticsProjection rebuild;

    @Autowired
    List<ApplicationRunner> runners;

    private String workspace;
    private String account;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        account = read(mvc.post().uri(ACCOUNTS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}").exchange(), "$.id").toString();
        IdentityTables.awaitQuiescence(jdbc);
    }

    @Test
    void renormalizesKeysMovesRulesAndRefreshesTheProjection(CapturedOutput output) throws Exception {
        String bob = createExpense("Compra do Bob", "4321.09");
        String lume = createExpense("Bistrô Lume", "10.00");
        asV1(bob, "do bob", "Do Bob");
        asV1(lume, "bistro lume", "Bistrô Lume");
        rule("do bob", "DINING_OUT", "2026-10-01T00:00:00Z");
        rule("bob", "GROCERIES", "2026-09-01T00:00:00Z");
        jdbc.update("update transactions set category_code = 'DINING_OUT', category_nature = 'EXPENSE',"
                + " category_source = 'MANUAL', category_rule_version = null where id = ?::uuid", bob);
        long eventsBefore = categorizedEvents();

        RenormalizeMerchants.Result result = renormalize.execute();

        assertThat(result).isEqualTo(new RenormalizeMerchants.Result(1, 2, 1));
        assertThat(merchant(bob)).isEqualTo("bob|Bob|v2|DINING_OUT|MANUAL");
        assertThat(merchant(lume)).isEqualTo("bistro lume|Bistrô Lume|v2|DINING_OUT|RULE_SYSTEM");
        // A regra mais recente ("do bob" → Alimentação fora) vence na chave nova; a antiga sai.
        assertThat(jdbc.queryForList("select merchant_key || '=' || category_code from category_rules"
                + " where workspace_id = ?::uuid order by 1", String.class, workspace))
                .containsExactly("bob=DINING_OUT");
        assertThat(categorizedEvents() - eventsBefore).isEqualTo(2);
        assertThat(renormalize.execute()).isEqualTo(new RenormalizeMerchants.Result(0, 0, 0));

        rebuild.execute(new WorkspaceId(UUID.fromString(workspace)));
        assertThat(jdbc.queryForList("select merchant_key from analytics_daily_flows where workspace_id = ?::uuid"
                + " order by 1", String.class, workspace)).containsExactly("bistro lume", "bob");

        assertThat(output.getAll()).contains("Merchants renormalized")
                .doesNotContain("4321").doesNotContain("Bob").doesNotContain("bob");
    }

    /** O rebuild do Analytics precisa ler as chaves já renormalizadas: a renormalização roda antes. */
    @Test
    void runsBeforeTheAnalyticsRebuild() {
        List<ApplicationRunner> ordered = new ArrayList<>(runners);
        AnnotationAwareOrderComparator.sort(ordered);

        assertThat(ordered.indexOf(runner(MerchantRenormalizationRunner.class, ordered)))
                .isLessThan(ordered.indexOf(runner(AnalyticsRebuildRunner.class, ordered)));
    }

    private static ApplicationRunner runner(Class<?> type, List<ApplicationRunner> runners) {
        return runners.stream().filter(type::isInstance).findFirst().orElseThrow();
    }

    private String createExpense(String description, String amount) throws Exception {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, workspace).with(ana())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "2026-09-22", "description": "%s"}
                        """.formatted(account, amount, description)).exchange();
        return read(result, "$.id").toString();
    }

    private void asV1(String id, String key, String name) {
        jdbc.update("update transactions set merchant_key = ?, merchant_name = ?, merchant_normalizer_version = 'v1'"
                + " where id = ?::uuid", key, name, id);
    }

    private void rule(String key, String category, String updatedAt) {
        jdbc.update("""
                insert into category_rules (workspace_id, merchant_key, category_code, category_nature, created_at,
                                            updated_at)
                values (?::uuid, ?, ?, 'EXPENSE', ?::timestamptz, ?::timestamptz)
                """, workspace, key, category, updatedAt, updatedAt);
    }

    private String merchant(String id) {
        return jdbc.queryForObject("""
                select merchant_key || '|' || merchant_name || '|' || merchant_normalizer_version || '|'
                       || coalesce(category_code, '') || '|' || coalesce(category_source, '')
                from transactions where id = ?::uuid
                """, String.class, id);
    }

    private long categorizedEvents() {
        return jdbc.queryForObject("select count(*) from outbox_events where workspace_id = ?::uuid"
                + " and event_type = 'transaction.categorized'", Long.class, workspace);
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(token -> token.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Nome"));
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
