package com.smarthealthfinance.analytics.infrastructure.messaging;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.analytics.application.usecase.RefreshAnalyticsProjection;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Ponta a ponta (ADR-0014): comando na API → {@code transaction.*} na outbox → relay → RabbitMQ → consumidor →
 * projeção e freshness. Também DLQ, concorrência e logs sem dado financeiro.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsWorkerIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String STATUS = "/api/v1/workspaces/{workspaceId}/analytics/status";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    RefreshAnalyticsProjection refresh;

    private String workspace;
    private String account;
    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        admin.purgeQueue(AnalyticsMessagingConfig.DEAD_LETTER_QUEUE, false);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        account = read(mvc.post().uri(ACCOUNTS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}").exchange(), "$.id").toString();
    }

    @Test
    void transactionCommandsReachTheProjectionThroughTheOutbox(CapturedOutput output) throws Exception {
        assertThat(status()).bodyJson()
                .hasPathSatisfying("$.state", s -> assertThat(s).asString().isEqualTo("UP_TO_DATE"));

        String lume = createExpense("4321.98", "Loja Sigilosa 22/09");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(rows("select category_code from analytics_daily_flows where workspace_id = ?::uuid"))
                    .containsExactly((String) null);
            assertThat(read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        });
        assertThat(read(status(), "$.refreshedAt")).isNotNull();

        assertThat(mvc.put().uri(TRANSACTIONS + "/{id}/category", workspace, lume).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryCode\": \"SHOPPING\"}").exchange())
                .hasStatus(HttpStatus.OK);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                rows("select category_code from analytics_daily_flows where workspace_id = ?::uuid"))
                .containsExactly("SHOPPING"));

        assertThat(mvc.post().uri(TRANSACTIONS + "/{id}/reverse", workspace, lume).with(ana()).exchange())
                .hasStatus(HttpStatus.OK);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                rows("select category_code from analytics_daily_flows where workspace_id = ?::uuid")).isEmpty());

        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events where workspace_id = ?::uuid and event_type like 'transaction.%'
                """, Integer.class, workspace)).isEqualTo(3);
        // Logs do consumidor só com ids, contagens e meses: nem valor, nem descrição, nem categoria.
        assertThat(output.getAll()).contains("Analytics projection")
                .doesNotContain("4321.98").doesNotContain("Sigilosa").doesNotContain("SHOPPING");
    }

    /** "Marcar como reembolso": os eventos reversed + created levam a entrada de receita para reembolso. */
    @Test
    void markingAsRefundMovesTheInflowToRefundsInTheProjection() throws Exception {
        MvcTestResult created = mvc.post().uri(TRANSACTIONS, workspace).with(ana())
                .header("Idempotency-Key", "k-" + ++keys).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "INCOME", "accountId": "%s", "amount": {"amount": "129.90", "currency": "BRL"},
                         "occurredOn": "2026-09-22", "description": "Estorno Loja Azul"}
                        """.formatted(account)).exchange();
        String income = read(created, "$.id").toString();
        jdbc.update("update transactions set source = 'IMPORT' where id = ?::uuid", income);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                rows("select flow || ' ' || amount::text from analytics_daily_flows where workspace_id = ?::uuid"))
                .containsExactly("INCOME 129.9000"));

        assertThat(mvc.post().uri(TRANSACTIONS + "/{id}/mark-as-refund", workspace, income).with(ana()).exchange())
                .hasStatus(HttpStatus.CREATED);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(rows("select flow || ' ' || amount::text from analytics_daily_flows where workspace_id = ?::uuid"))
                    .containsExactly("REFUND 129.9000");
            assertThat(read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        });
    }

    @Test
    void statusRequiresMembership() {
        mvc.post().uri(ME).with(bob()).exchange();
        assertThat(mvc.get().uri(STATUS, workspace).with(bob()).exchange()).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
        assertThat(mvc.get().uri(STATUS, workspace).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedEventGoesToTheDeadLetterQueue() {
        rabbit.send("", AnalyticsMessagingConfig.QUEUE,
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(AnalyticsMessagingConfig.DEAD_LETTER_QUEUE).getMessageCount()).isEqualTo(1));
    }

    /** O lock do Workspace serializa os recálculos: nunca dois delete + insert concorrentes no mesmo mês. */
    @Test
    void concurrentRefreshesOfTheSameMonthNeverDuplicateRows() throws Exception {
        createExpense("10.00", "Loja Alfa");
        createExpense("20.00", "Loja Beta");
        IdentityTables.awaitQuiescence(jdbc);
        String expected = projection();
        WorkspaceId id = new WorkspaceId(UUID.fromString(workspace));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<RefreshAnalyticsProjection.Outcome>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> refresh.execute(UUID.randomUUID(), Instant.now(), id,
                        Set.of(YearMonth.of(2026, 9))));
            }
            for (Future<RefreshAnalyticsProjection.Outcome> result : pool.invokeAll(calls)) {
                assertThat(result.get()).isEqualTo(RefreshAnalyticsProjection.Outcome.REFRESHED);
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(projection()).isEqualTo(expected);
    }

    private String createExpense(String amount, String description) throws Exception {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, workspace).with(ana())
                .header("Idempotency-Key", "k-" + ++keys).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "2026-09-22", "description": "%s"}
                        """.formatted(account, amount, description)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private MvcTestResult status() {
        return mvc.get().uri(STATUS, workspace).with(ana()).exchange();
    }

    private List<String> rows(String sql) {
        return jdbc.queryForList(sql, String.class, workspace);
    }

    private String projection() {
        return jdbc.queryForObject("""
                select coalesce(string_agg(f::text, '|' order by f::text), '')
                from (select day, account_id, flow, category_code, merchant_key, amount, transaction_count
                      from analytics_daily_flows where workspace_id = ?::uuid) f
                """, String.class, workspace);
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
