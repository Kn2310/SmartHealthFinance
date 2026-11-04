package com.smarthealthfinance.forecast.infrastructure.messaging;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.service.ForecastInputLoader;
import com.smarthealthfinance.forecast.domain.enums.InvoiceProjectionKind;
import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Ponta a ponta do Forecast (ADR-0031 §2, §3, §9, §10): importação de uma fatura de cartão → {@code transaction.*} →
 * Analytics → {@code analytics.projection-updated} → marca com debounce → job → snapshot com a fatura →
 * {@code forecast.snapshot-updated} → fila dos Insights; também reentrega, teto do debounce, DLQ, status e logs sem
 * dado financeiro. O {@code SKIP LOCKED} concorrente e o congelamento do mês ficam em {@code ForecastStoresIT} e
 * {@code ForecastSnapshotParityIT} (mesmo banco real).
 * <p>
 * Fatura (cartão Aurora): compra de 86,40 hoje, parcela 3/10 de 480,00 e estorno de 20,00 → 546,40 na fatura aberta.
 */
@ExtendWith(OutputCaptureExtension.class)
class ForecastWorkerIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String WS = "/api/v1/workspaces/{workspaceId}";
    private static final String STATUS = WS + "/forecast/status";
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    ForecastSnapshotStore store;

    @Autowired
    ForecastInputLoader loader;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MeterRegistry meters;

    private LocalDate today;
    private BillingCycle cycle;
    private InvoiceDates current;
    private LocalDate notebookDate;
    private String workspace;
    private String account;
    private String card;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        admin.purgeQueue(ForecastMessagingConfig.DEAD_LETTER_QUEUE, false);
        today = loader.today();
        // Ciclo em que hoje fica depois do vencimento da fatura anterior ao acompanhamento (como no import de cartão).
        cycle = today.getDayOfMonth() >= 6 && today.getDayOfMonth() <= 27 ? new BillingCycle(28, 5)
                : new BillingCycle(15, 22);
        current = new InvoiceCalendar(cycle, List.of()).invoiceOf(today);
        notebookDate = current.periodStart().minusMonths(2);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId");
        account = read(post(WS + "/accounts", """
                {"name": "Conta Aurora", "type": "CHECKING"}
                """), "$.id");
        card = read(post(WS + "/cards", """
                {"nickname": "Aurora Platinum", "brand": "MASTERCARD", "last4": "4821",
                 "limit": {"amount": "12000.00", "currency": "BRL"}, "closingDay": %d, "dueDay": %d,
                 "defaultPaymentAccountId": "%s"}
                """.formatted(cycle.closingDay(), cycle.dueDay(), account)), "$.id");
    }

    @Test
    void importedCardInvoiceReachesTheSnapshotAndTheEventReachesTheInsightsQueue(CapturedOutput output)
            throws Exception {
        importInvoice();
        IdentityTables.awaitQuiescence(jdbc);

        ForecastResult result = awaitSnapshot(r -> openInvoice(r) != null
                && openInvoice(r).total().factPart().amount().compareTo(new BigDecimal("546.40")) == 0);

        assertThat(result.algorithmVersion()).isEqualTo("forecast-v1");
        assertThat(result.asOf()).isEqualTo(today);
        IdentityTables.awaitQuiescence(jdbc);

        // O recálculo gravou o evento na outbox; o relay o publicou e a fila dos Insights o consumiu.
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events where workspace_id = ?::uuid
                  and event_type = 'forecast.snapshot-updated' and published_at is not null
                  and payload ->> 'algorithmVersion' = 'forecast-v1'
                """, Integer.class, workspace)).isPositive();
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events o where o.workspace_id = ?::uuid
                  and o.event_type = 'forecast.snapshot-updated' and not exists (
                    select 1 from processed_events p where p.consumer = 'insights.generation' and p.event_id = o.id)
                """, Integer.class, workspace)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from processed_events p join outbox_events o on o.id = p.event_id
                where p.consumer = 'insights.generation' and o.workspace_id = ?::uuid
                  and o.event_type = 'forecast.snapshot-updated'""", Integer.class, workspace)).isPositive();
        assertThat(jdbc.queryForObject("""
                select count(*) from processed_events p join outbox_events o on o.id = p.event_id
                where p.consumer = 'forecast.snapshot' and o.workspace_id = ?::uuid
                  and o.event_type = 'analytics.projection-updated'""", Integer.class, workspace)).isPositive();

        // Status e métricas.
        assertThat((String) read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        assertThat((String) read(status(), "$.asOf")).isEqualTo(today.toString());
        assertThat((Object) read(status(), "$.computedAt")).isNotNull();
        assertThat(meters.find("shf.forecast.snapshot.marked").counter().count()).isPositive();
        assertThat(meters.find("shf.forecast.snapshot.freshness").gauge()).isNotNull();

        // Logs só com ids, contagens e estados: nem valores, nem descrições, nem apelido do cartão.
        assertThat(output.getAll()).contains("Forecast snapshot recalculated")
                .doesNotContain("546.40").doesNotContain("4800.00").doesNotContain("86.40")
                .doesNotContain("Loja Tech").doesNotContain("Bistr").doesNotContain("Notebook")
                .doesNotContain("Aurora Platinum");
    }

    @Test
    void redeliveryOfAnEventIsIgnored() throws Exception {
        importInvoice();
        awaitSnapshot(r -> true);
        IdentityTables.awaitQuiescence(jdbc);
        String processed = jdbc.queryForObject("""
                select p.event_id::text from processed_events p join outbox_events o on o.id = p.event_id
                where p.consumer = 'forecast.snapshot' and o.workspace_id = ?::uuid limit 1
                """, String.class, workspace);
        Message original = new Message(jdbc.queryForObject("""
                select json_build_object('eventId', id, 'eventType', event_type, 'eventVersion', event_version,
                  'occurredAt', occurred_at, 'publishedAt', published_at, 'workspaceId', workspace_id,
                  'aggregateType', aggregate_type, 'aggregateId', aggregate_id, 'payload', payload)::text
                from outbox_events where id = ?::uuid
                """, String.class, processed).getBytes(StandardCharsets.UTF_8), new MessageProperties());
        String computedAt = computedAt();

        rabbit.send("", ForecastMessagingConfig.QUEUE, original);

        // Se a reentrega marcasse o Workspace, o job recalcularia em até 1,2 s (teto de 1 s + ciclo de 200 ms).
        await().pollDelay(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(computedAt()).isEqualTo(computedAt);
            assertThat(jdbc.queryForObject("select dirty_since is null from forecast_workspaces"
                    + " where workspace_id = ?::uuid", Boolean.class, workspace)).isTrue();
        });
    }

    /** Eventos contínuos adiam o recálculo, mas nunca além do teto: o snapshot sai antes de o fluxo acabar. */
    @Test
    void aContinuousStreamOfEventsStillRecalculatesWithinTheCap() throws Exception {
        importInvoice();
        awaitSnapshot(r -> true);
        IdentityTables.awaitQuiescence(jdbc);
        String before = computedAt();
        int events = 25;
        long start = System.nanoTime();
        long firstRecalculation = -1;

        for (int i = 0; i < events; i++) {
            rabbit.send("", ForecastMessagingConfig.QUEUE, envelope("account.changed"));
            Thread.sleep(150);
            if (firstRecalculation < 0 && !computedAt().equals(before)) {
                firstRecalculation = Duration.ofNanos(System.nanoTime() - start).toMillis();
            }
        }

        long streamMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(firstRecalculation).as("recalculou durante o fluxo").isPositive().isLessThan(streamMillis - 500);
        // O debounce junta eventos: bem menos recálculos do que eventos.
        IdentityTables.awaitQuiescence(jdbc);
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events where workspace_id = ?::uuid
                  and event_type = 'forecast.snapshot-updated'""", Integer.class, workspace)).isLessThan(events);
    }

    @Test
    void malformedAndUnexpectedEventsGoToTheDeadLetterQueue() {
        rabbit.send("", ForecastMessagingConfig.QUEUE,
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        // O Forecast não consome eventos de origem nem os do Health.
        rabbit.send("", ForecastMessagingConfig.QUEUE, envelope("transaction.created"));
        rabbit.send("", ForecastMessagingConfig.QUEUE, envelope("health.snapshot-updated"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(ForecastMessagingConfig.DEAD_LETTER_QUEUE).getMessageCount()).isEqualTo(3));
    }

    @Test
    void statusFollowsTheSnapshotLifecycle() throws Exception {
        // Sem transações: nada a construir.
        assertThat((String) read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        assertThat((Object) read(status(), "$.computedAt")).isNull();
        assertThat((Object) read(status(), "$.asOf")).isNull();

        importInvoice();
        awaitSnapshot(r -> true);
        IdentityTables.awaitQuiescence(jdbc);
        assertThat((String) read(status(), "$.state")).isEqualTo("UP_TO_DATE");

        // Workspace marcado para recálculo (vence daqui a uma hora: o job não o pega durante o teste).
        jdbc.update("update forecast_workspaces set dirty_since = now(), due_at = now() + interval '1 hour'"
                + " where workspace_id = ?::uuid", workspace);
        assertThat((String) read(status(), "$.state")).isEqualTo("RECALCULATING");

        // Há transações e nenhum snapshot na versão.
        jdbc.update("update forecast_workspaces set dirty_since = null, due_at = null where workspace_id = ?::uuid",
                workspace);
        jdbc.update("delete from forecast_snapshots where workspace_id = ?::uuid", workspace);
        assertThat((String) read(status(), "$.state")).isEqualTo("NOT_BUILT");
        assertThat((Object) read(status(), "$.asOf")).isNull();
    }

    /** ADR-0031 §7: os Insights respondem UPDATING também enquanto o Forecast recalcula. */
    @Test
    void insightsStatusIsUpdatingWhileTheForecastRecalculates() throws Exception {
        importInvoice();
        awaitSnapshot(r -> true);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            IdentityTables.awaitQuiescence(jdbc);
            assertThat((String) read(insightsStatus(), "$.state")).isEqualTo("UP_TO_DATE");
        });

        jdbc.update("update forecast_workspaces set dirty_since = now(), due_at = now() + interval '1 hour'"
                + " where workspace_id = ?::uuid", workspace);
        assertThat((String) read(insightsStatus(), "$.state")).isEqualTo("UPDATING");

        jdbc.update("update forecast_workspaces set dirty_since = null, due_at = null where workspace_id = ?::uuid",
                workspace);
        assertThat((String) read(insightsStatus(), "$.state")).isEqualTo("UP_TO_DATE");
    }

    @Test
    void aSnapshotFromAPreviousDayIsMarkedOnReadAndRecalculated() throws Exception {
        importInvoice();
        awaitSnapshot(r -> true);
        IdentityTables.awaitQuiescence(jdbc);
        jdbc.update("update forecast_snapshots set as_of = as_of - 1 where workspace_id = ?::uuid", workspace);

        assertThat((String) read(status(), "$.state")).isEqualTo("RECALCULATING");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat((String) read(status(), "$.state")).isEqualTo("UP_TO_DATE");
            assertThat((String) read(status(), "$.asOf")).isEqualTo(today.toString());
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

    // --- helpers ---

    private void importInvoice() throws Exception {
        String csv = """
                data;descricao;valor;parcela
                %s;Loja Tech [exemplo] · Notebook;480,00;3/10
                %s;Bistrô Lume;86,40;
                %s;Estorno Mercado;-20,00;
                """.formatted(notebookDate.format(BR), today.format(BR), today.format(BR));
        MvcTestResult upload = mvc.post().uri(WS + "/imports", workspace).multipart()
                .file(new MockMultipartFile("file", "fatura.csv", "application/octet-stream",
                        csv.getBytes(StandardCharsets.UTF_8)))
                .param("cardId", card)
                .header("Idempotency-Key", "k-" + UUID.randomUUID())
                .with(ana()).exchange();
        assertThat(upload).hasStatus(HttpStatus.CREATED);
        String importId = read(upload, "$.id");
        assertThat(mvc.post().uri(WS + "/imports/{importId}/confirm", workspace, importId).with(ana()).exchange())
                .hasStatus(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat((String) read(mvc.get().uri(WS + "/imports/{importId}", workspace, importId).with(ana())
                        .exchange(), "$.status")).isEqualTo("COMPLETED"));
    }

    private CardInvoiceProjection openInvoice(ForecastResult result) {
        return result.invoices().stream()
                .filter(i -> i.kind() == InvoiceProjectionKind.OPEN && i.card().id().toString().equals(card))
                .findFirst().orElse(null);
    }

    private ForecastResult awaitSnapshot(Predicate<ForecastResult> condition) {
        return await().atMost(Duration.ofSeconds(30)).until(() -> new TransactionTemplate(transactionManager)
                .execute(status -> store.find(id(), ForecastCalculator.ALGORITHM_VERSION).orElse(null)),
                result -> result != null && condition.test(result));
    }

    /** Envelope de evento do Workspace do teste (como o relay da outbox o publica). */
    private Message envelope(String type) {
        return new Message("""
                {"eventId": "%s", "eventType": "%s", "eventVersion": 1,
                 "occurredAt": "2026-09-22T10:00:00Z", "publishedAt": "2026-09-22T10:00:01Z",
                 "workspaceId": "%s", "aggregateType": "Account", "aggregateId": "%s", "payload": {}}
                """.formatted(UUID.randomUUID(), type, workspace, UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private WorkspaceId id() {
        return new WorkspaceId(UUID.fromString(workspace));
    }

    private String computedAt() {
        return jdbc.queryForObject("select computed_at::text from forecast_workspaces where workspace_id = ?::uuid",
                String.class, workspace);
    }

    private MvcTestResult status() {
        return mvc.get().uri(STATUS, workspace).with(ana()).exchange();
    }

    private MvcTestResult insightsStatus() {
        return mvc.get().uri(WS + "/insights/status", workspace).with(ana()).exchange();
    }

    private MvcTestResult post(String uri, String json) {
        return mvc.post().uri(uri, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
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

    @SuppressWarnings("unchecked")
    private static <T> T read(MvcTestResult result, String path) throws Exception {
        return (T) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
