package com.smarthealthfinance.health.infrastructure.messaging;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.service.HealthInputLoader;
import com.smarthealthfinance.health.application.service.HealthSnapshotCalculator;
import com.smarthealthfinance.health.domain.enums.DimensionStatus;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.HealthState;
import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Ponta a ponta (ADR-0018): comando na API → {@code transaction.*} → projeção de Analytics →
 * {@code analytics.projection-updated} → marca com debounce → job → snapshot; também {@code account.changed},
 * paridade com o cálculo direto sobre as portas, DLQ e logs sem dado financeiro.
 * <p>
 * Datas relativas a hoje: 3 meses fechados e o corrente, todos com salário de 5.000 e despesa de 3.000 no dia 1 e
 * reserva de 9.000 numa poupança fora do total. Os valores esperados valem também no último dia do mês (quando o
 * corrente conta como fechado): Cash Flow 100, Spending 100, Reserve 80 (3,00 meses), Consistency 100 →
 * (2.500 + 1.500 + 1.600 + 1.000) ÷ 70 = 94,29 → 94.
 */
@ExtendWith(OutputCaptureExtension.class)
class HealthWorkerIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String STATUS = "/api/v1/workspaces/{workspaceId}/health/status";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    HealthSnapshotStore store;

    @Autowired
    HealthInputLoader loader;

    @Autowired
    PlatformTransactionManager transactionManager;

    private String workspace;
    private String checking;
    private String savings;
    private YearMonth current;
    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        admin.purgeQueue(HealthMessagingConfig.DEAD_LETTER_QUEUE, false);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        checking = createAccount("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}");
        savings = createAccount("{\"name\": \"Banco Norte\", \"type\": \"SAVINGS\", \"includedInTotal\": false}");
        current = YearMonth.from(loader.today());
    }

    @Test
    void snapshotFollowsTransactionsThroughAnalytics(CapturedOutput output) throws Exception {
        seedHistory();
        // Todos os eventos processados e nenhum Workspace marcado: o snapshot é o final.
        IdentityTables.awaitQuiescence(jdbc);

        HealthResult now = awaitSnapshot(r -> r.score() != null && r.dimensions().stream()
                .filter(d -> d.dimension() == HealthDimension.CONSISTENCY).anyMatch(DimensionResult::isOk));

        assertThat(now.algorithmVersion()).isEqualTo("health-v1");
        assertThat(now.state()).isEqualTo(HealthState.READY);
        assertThat(score(now, HealthDimension.CASH_FLOW)).isEqualTo(100);
        assertThat(score(now, HealthDimension.SPENDING)).isEqualTo(100);
        assertThat(score(now, HealthDimension.RESERVE)).isEqualTo(80);
        assertThat(score(now, HealthDimension.CONSISTENCY)).isEqualTo(100);
        assertThat(now.score()).isEqualTo(94);
        assertThat(now.variation()).isZero();
        assertThat(now.coverage()).isEqualTo(new HealthResult.Coverage(4, 6, 70));
        // "Aluguel" é categorizado como Moradia pelas regras do sistema (ADR-0013): nada sem categoria.
        assertThat(now.uncategorizedShare()).isEqualByComparingTo("0");
        assertThat(now.nextSteps()).extracting(s -> s.kind()).containsExactly(NextStepKind.BUILD_RESERVE);
        assertThat(now.nextSteps().getFirst().amount().amount()).isEqualByComparingTo("9000.00");

        // Mês corrente + os fechados desde o primeiro lançamento (3 meses antes).
        assertThat(jdbc.queryForObject("select count(*) from health_snapshots where workspace_id = ?::uuid",
                Integer.class, workspace)).isEqualTo(4);
        assertThat(read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        assertThat(read(status(), "$.computedAt")).isNotNull();

        // Paridade: o snapshot gravado é o cálculo direto sobre as portas de Analytics e Overview.
        List<HealthResult> direct = new TransactionTemplate(transactionManager).execute(status ->
                HealthSnapshotCalculator.calculate(loader.load(id())));
        assertThat(store(current.minusMonths(3), current)).isEqualTo(direct);

        // Logs do Health só com ids e contagens: nem valor, nem descrição, nem texto de explicação.
        assertThat(output.getAll()).contains("Health snapshot recalculated")
                .doesNotContain("9000.00").doesNotContain("Empresa Ficticia").doesNotContain("Sua reserva cobre");
    }

    @Test
    void accountChangesRecalculateWithoutAnyTransaction() throws Exception {
        seedHistory();
        IdentityTables.awaitQuiescence(jdbc);
        awaitSnapshot(r -> r.score() != null && score(r, HealthDimension.RESERVE) != null
                && score(r, HealthDimension.CONSISTENCY) != null);

        assertThat(mvc.put().uri(ACCOUNTS + "/{id}", workspace, savings).with(ana())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Banco Norte\", \"type\": \"CHECKING\", \"includedInTotal\": false}").exchange())
                .hasStatus(HttpStatus.OK);

        HealthResult now = awaitSnapshot(r -> dimension(r, HealthDimension.RESERVE).status()
                == DimensionStatus.INSUFFICIENT_DATA);
        assertThat(now.nextSteps()).extracting(s -> s.kind()).startsWith(NextStepKind.ADD_RESERVE_ACCOUNT);
        // (2.500 + 1.500 + 1.000) ÷ 50 = 100.
        assertThat(now.score()).isEqualTo(100);
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events where workspace_id = ?::uuid and event_type = 'account.changed'
                """, Integer.class, workspace)).isEqualTo(3);
    }

    @Test
    void redeliveryOfAnEventIsIgnored() throws Exception {
        createIncome(checking, current.atDay(1), "5000.00");
        awaitSnapshot(r -> true);
        IdentityTables.awaitQuiescence(jdbc);
        String processed = jdbc.queryForObject("""
                select p.event_id::text from processed_events p join outbox_events o on o.id = p.event_id
                where p.consumer = 'health.snapshot' and o.workspace_id = ?::uuid limit 1
                """, String.class, workspace);
        Message original = new Message(jdbc.queryForObject("""
                select json_build_object('eventId', id, 'eventType', event_type, 'eventVersion', event_version,
                  'occurredAt', occurred_at, 'publishedAt', published_at, 'workspaceId', workspace_id,
                  'aggregateType', aggregate_type, 'aggregateId', aggregate_id, 'payload', payload)::text
                from outbox_events where id = ?::uuid
                """, String.class, processed).getBytes(StandardCharsets.UTF_8), new MessageProperties());

        String computedAt = computedAt();

        rabbit.send("", HealthMessagingConfig.QUEUE, original);

        // Se a reentrega marcasse o Workspace, o job recalcularia em até 1,2 s (teto de 1 s + ciclo de 200 ms).
        await().pollDelay(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(computedAt()).isEqualTo(computedAt);
            assertThat(jdbc.queryForObject("select dirty_since is null from health_workspaces"
                    + " where workspace_id = ?::uuid", Boolean.class, workspace)).isTrue();
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
    void workspaceWithoutTransactionsIsUpToDateWithoutSnapshot() throws Exception {
        assertThat(read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        assertThat(read(status(), "$.computedAt")).isNull();
    }

    @Test
    void malformedAndUnexpectedEventsGoToTheDeadLetterQueue() {
        rabbit.send("", HealthMessagingConfig.QUEUE,
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        rabbit.send("", HealthMessagingConfig.QUEUE, new Message("""
                {"eventId": "%s", "eventType": "transaction.created", "eventVersion": 1,
                 "occurredAt": "2026-09-22T10:00:00Z", "publishedAt": "2026-09-22T10:00:01Z",
                 "workspaceId": "%s", "aggregateType": "Transaction", "aggregateId": "%s", "payload": {}}
                """.formatted(UUID.randomUUID(), workspace, UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
                new MessageProperties()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(HealthMessagingConfig.DEAD_LETTER_QUEUE).getMessageCount()).isEqualTo(2));
    }

    private void seedHistory() throws Exception {
        createIncome(savings, current.minusMonths(3).atDay(1), "9000.00");
        for (int back = 3; back >= 0; back--) {
            LocalDate day = current.minusMonths(back).atDay(1);
            createIncome(checking, day, "5000.00");
            createTransaction("EXPENSE", checking, day, "3000.00", "Aluguel Casa");
        }
    }

    private HealthResult awaitSnapshot(java.util.function.Predicate<HealthResult> condition) {
        return await().atMost(Duration.ofSeconds(30)).until(() -> new TransactionTemplate(transactionManager)
                .execute(status -> store.find(id(), HealthCalculator.ALGORITHM_VERSION, current).orElse(null)),
                result -> result != null && condition.test(result));
    }

    private List<HealthResult> store(YearMonth from, YearMonth to) {
        return new TransactionTemplate(transactionManager).execute(status ->
                store.findMonths(id(), HealthCalculator.ALGORITHM_VERSION, from, to));
    }

    private static DimensionResult dimension(HealthResult result, HealthDimension dimension) {
        return result.dimensions().stream().filter(d -> d.dimension() == dimension).findFirst().orElseThrow();
    }

    private static Integer score(HealthResult result, HealthDimension dimension) {
        return dimension(result, dimension).score();
    }

    private WorkspaceId id() {
        return new WorkspaceId(UUID.fromString(workspace));
    }

    private String createAccount(String body) throws Exception {
        MvcTestResult result = mvc.post().uri(ACCOUNTS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private void createIncome(String account, LocalDate day, String amount) throws Exception {
        createTransaction("INCOME", account, day, amount, "Empresa Ficticia");
    }

    private void createTransaction(String type, String account, LocalDate day, String amount, String description)
            throws Exception {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, workspace).with(ana())
                .header("Idempotency-Key", "h-" + ++keys).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "%s", "description": "%s"}
                        """.formatted(type, account, amount, day, description)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private String computedAt() {
        return jdbc.queryForObject("select computed_at::text from health_workspaces where workspace_id = ?::uuid",
                String.class, workspace);
    }

    private MvcTestResult status() {
        return mvc.get().uri(STATUS, workspace).with(ana()).exchange();
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
