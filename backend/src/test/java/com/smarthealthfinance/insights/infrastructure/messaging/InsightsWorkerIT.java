package com.smarthealthfinance.insights.infrastructure.messaging;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.service.InsightGenerator;
import com.smarthealthfinance.insights.application.service.InsightInputLoader;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import com.smarthealthfinance.insights.domain.service.InsightTexts;
import com.smarthealthfinance.insights.domain.valueobject.EvaluationPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Ponta a ponta (ADR-0022): comando na API → {@code transaction.*} → projeção → {@code analytics.projection-updated}
 * → snapshot do Health → {@code health.snapshot-updated} → marca com debounce → job → insights; paridade com a geração
 * direta sobre as portas, regeneração idempotente, dispensa respeitada, resolução, DLQ, status e logs sem dado
 * financeiro.
 * <p>
 * Datas relativas a hoje, no período avaliado P (o mês corrente a partir do dia 7; antes, o anterior): nos 5 meses
 * antes de P, salário de 5.000 e "Restaurante Sabor" de 300 no dia 1; em P, o mesmo salário, 900 no restaurante e uma
 * compra sem categoria de 600. Dispara D1 (alimentação fora +200%), D2 (total +400%) e D10 (40% sem categoria) em
 * qualquer dia do mês.
 */
@ExtendWith(OutputCaptureExtension.class)
class InsightsWorkerIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final Currency BRL = Currency.getInstance("BRL");
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";
    private static final String STATUS = "/api/v1/workspaces/{workspaceId}/insights/status";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    InsightStore store;

    @Autowired
    InsightInputLoader loader;

    @Autowired
    ForecastSnapshotStore forecastStore;

    @Autowired
    RecurringSeriesRepository seriesRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private String workspace;
    private String checking;
    private EvaluationPeriod period;
    private String uncategorized;
    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        admin.purgeQueue(InsightsMessagingConfig.DEAD_LETTER_QUEUE, false);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        checking = createAccount();
        period = EvaluationPeriod.of(loader.today());
    }

    @Test
    void insightsFollowTheHealthSnapshot(CapturedOutput output) throws Exception {
        seed();
        IdentityTables.awaitQuiescence(jdbc);
        List<Insight> active = awaitActive(list -> detectors(list).containsAll(List.of(
                InsightDetector.CATEGORY_SPIKE, InsightDetector.SPENDING_ABOVE_PATTERN,
                InsightDetector.UNCATEGORIZED_SHARE)));

        String in = InsightTexts.inPeriod(period.month(), period.partial());
        Insight spike = one(active, InsightDetector.CATEGORY_SPIKE);
        assertThat(spike.algorithmVersion()).isEqualTo("insights-v3");
        assertThat(spike.periodKey()).isEqualTo(period.key());
        assertThat(spike.subject()).isEqualTo("DINING_OUT");
        assertThat(spike.impactAmount().amount()).isEqualByComparingTo("600.00");
        assertThat(spike.importance()).isEqualTo(Importance.IMPORTANT);
        assertThat(spike.content().title())
                .isEqualTo("Seus gastos com alimentação fora aumentaram 200% " + in + ", cerca de R$ 600 a mais.");
        assertThat(one(active, InsightDetector.SPENDING_ABOVE_PATTERN).content().title())
                .isEqualTo("Seus gastos estão 400% acima do seu padrão " + in + ", cerca de R$ 1.200 a mais.");
        assertThat(one(active, InsightDetector.UNCATEGORIZED_SHARE).content().title()).isEqualTo("40% dos seus gastos "
                + (period.partial() ? "deste mês" : "de " + InsightTexts.monthName(period.month()))
                + " estão sem categoria.");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events where workspace_id = ?::uuid
                  and event_type = 'health.snapshot-updated'
                """, Integer.class, workspace)).isPositive();
        assertThat(read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        assertThat(read(status(), "$.generatedAt")).isNotNull();

        // Paridade: o que foi gravado é a geração direta sobre as portas de Analytics e Health.
        List<ScoredCandidate> direct = new TransactionTemplate(transactionManager).execute(status ->
                InsightGenerator.score(id().value(), loader.load(id()), store.findAll(id()), Instant.now()));
        assertThat(active).extracting(Insight::fingerprint, Insight::content, Insight::impact, Insight::importance)
                .containsExactlyInAnyOrderElementsOf(direct.stream().map(s -> org.assertj.core.groups.Tuple.tuple(
                        s.fingerprint(), s.candidate().content(), s.impact(), s.importance())).toList());

        // Logs dos Insights só com ids e contagens: nem valor, nem texto, nem categoria.
        assertThat(output.getAll()).contains("Insights generated")
                .doesNotContain("Seus gastos com").doesNotContain("Sabor").doesNotContain("SABOR")
                .doesNotContain("DINING_OUT").doesNotContain("600.00");
    }

    @Test
    void regeneratingTheSameDataChangesNothing() throws Exception {
        seed();
        IdentityTables.awaitQuiescence(jdbc);
        List<Insight> before = awaitActive(list -> list.size() >= 3);
        String generatedAt = generatedAt();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                store.markDirty(id(), Instant.now(), Duration.ZERO, Duration.ZERO));
        await().atMost(Duration.ofSeconds(10)).until(() -> !generatedAt().equals(generatedAt));

        assertThat(all()).isEqualTo(before);
        assertThat(before).allSatisfy(i -> assertThat(i.revision()).isEqualTo(1));
    }

    @Test
    void dismissedStaysDismissedAndRecategorizingResolves() throws Exception {
        seed();
        IdentityTables.awaitQuiescence(jdbc);
        Insight spike = one(awaitActive(list -> list.size() >= 3), InsightDetector.CATEGORY_SPIKE);
        jdbc.update("update insights set status = 'DISMISSED', dismissed_at = now() where id = ?", spike.id());

        // A compra sem categoria vira Compras: D10 resolve; a alta de alimentação fora segue dispensada.
        assertThat(mvc.put().uri(TRANSACTIONS + "/{id}/category", workspace, uncategorized).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryCode\": \"SHOPPING\"}").exchange())
                .hasStatus(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(all())
                .filteredOn(i -> i.detector() == InsightDetector.UNCATEGORIZED_SHARE)
                .extracting(Insight::status).containsExactly(InsightStatus.RESOLVED));
        IdentityTables.awaitQuiescence(jdbc);
        assertThat(all()).filteredOn(i -> i.fingerprint().equals(spike.fingerprint()))
                .extracting(Insight::status).containsExactly(InsightStatus.DISMISSED);
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
    void workspaceWithoutTransactionsIsUpToDateWithoutGeneration() throws Exception {
        assertThat(read(status(), "$.state")).isEqualTo("UP_TO_DATE");
        assertThat(read(status(), "$.generatedAt")).isNull();
    }

    @Test
    void malformedAndUnexpectedEventsGoToTheDeadLetterQueue() {
        rabbit.send("", InsightsMessagingConfig.QUEUE,
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        rabbit.send("", InsightsMessagingConfig.QUEUE, new Message("""
                {"eventId": "%s", "eventType": "analytics.projection-updated", "eventVersion": 1,
                 "occurredAt": "2026-10-16T10:00:00Z", "publishedAt": "2026-10-16T10:00:01Z",
                 "workspaceId": "%s", "aggregateType": "AnalyticsProjection", "aggregateId": "%s", "payload": {}}
                """.formatted(UUID.randomUUID(), workspace, UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
                new MessageProperties()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(InsightsMessagingConfig.DEAD_LETTER_QUEUE).getMessageCount()).isEqualTo(2));
    }

    /**
     * ADR-0032 §3: o snapshot do Forecast com saldo projetado negativo (R$ 300 em conta, aluguel de R$ 1.000 nos
     * próximos 30 dias, sem receita recorrente) gera o D15 com a ação "Ver próximos eventos"; com o saldo recuperado
     * o insight resolve.
     */
    @Test
    void balanceMayGoNegativeFollowsTheForecastSnapshot() throws Exception {
        seed();
        IdentityTables.awaitQuiescence(jdbc);
        assertThat(all()).extracting(Insight::detector).doesNotContain(InsightDetector.BALANCE_MAY_GO_NEGATIVE);

        ForecastResult tight = forecast("300.00");
        assertThat(tight.cashFlow().troughNext30().lowestLowBound().low().amount().signum()).isNegative();
        saveForecast(tight);

        Insight d15 = await().atMost(Duration.ofSeconds(30)).until(
                () -> all().stream().filter(i -> i.detector() == InsightDetector.BALANCE_MAY_GO_NEGATIVE)
                        .filter(Insight::visible).findFirst(), Optional::isPresent).orElseThrow();
        assertThat(d15.algorithmVersion()).isEqualTo("insights-v3");
        assertThat(d15.periodKey()).isNull();
        assertThat(d15.status()).isEqualTo(InsightStatus.ACTIVE);
        assertThat(d15.content().title()).startsWith("Seu saldo pode ficar negativo por volta de ");
        assertThat(d15.content().primaryAction().kind()).isEqualTo(ActionKind.VIEW_UPCOMING_EVENTS);
        assertThat(d15.content().evidence().contributors()).isNotEmpty().hasSizeLessThanOrEqualTo(3);

        // Saldo recuperado: o D15 deixa de ser detectado e resolve (mesma identidade).
        saveForecast(forecast("10000.00"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(all())
                .filteredOn(i -> i.fingerprint().equals(d15.fingerprint()))
                .extracting(Insight::status).containsExactly(InsightStatus.RESOLVED));
    }

    private ForecastResult forecast(String balance) {
        LocalDate today = loader.today();
        UUID account = UUID.randomUUID();
        List<ForecastEntry> entries = new ArrayList<>();
        int sequence = 0;
        for (int k = 4; k >= 1; k--) {
            YearMonth month = YearMonth.from(today).minusMonths(k);
            for (Object[] row : new Object[][]{{15, "ALUGUEL", "1000.00"}, {8, "MERCADO", "150.55"},
                    {20, "MERCADO", "149.45"}}) {
                entries.add(new ForecastEntry(UUID.nameUUIDFromBytes(("tx-" + sequence++).getBytes(StandardCharsets.UTF_8)),
                        month.atDay((Integer) row[0]), CashFlowRole.EXPENSE, "moradia", (String) row[1],
                        (String) row[1], Money.of((String) row[2], BRL), Channel.account(account), false));
            }
        }
        LocalDate first = YearMonth.from(today).minusMonths(4).atDay(8);
        return ForecastCalculator.calculate(new ForecastInput(today, BRL, Optional.of(first), entries, List.of(),
                Money.of(balance, BRL), List.of(), List.of()));
    }

    private void saveForecast(ForecastResult result) {
        Instant now = Instant.now();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            seriesRepository.upsertDetected(id(), result.series().stream().map(SeriesAssessment::series).toList(),
                    now);
            forecastStore.replace(id(), result, now);
            store.markDirty(id(), now, Duration.ZERO, Duration.ZERO);
        });
    }

    private void seed() throws Exception {
        YearMonth p = period.month();
        for (int back = 5; back >= 0; back--) {
            YearMonth month = p.minusMonths(back);
            create("INCOME", month.atDay(1).toString(), "5000.00", "Salario Empresa Ficticia");
            create("EXPENSE", month.atDay(1).toString(), back == 0 ? "900.00" : "300.00", "Restaurante Sabor");
        }
        uncategorized = create("EXPENSE", p.atDay(1).toString(), "600.00", "Compra Loja Xyz");
    }

    private List<Insight> awaitActive(Predicate<List<Insight>> condition) {
        return await().atMost(Duration.ofSeconds(30)).until(
                () -> all().stream().filter(Insight::visible).toList(), condition);
    }

    private List<Insight> all() {
        return new TransactionTemplate(transactionManager).execute(status -> store.findAll(id()));
    }

    private static List<InsightDetector> detectors(List<Insight> insights) {
        return insights.stream().map(Insight::detector).toList();
    }

    private static Insight one(List<Insight> insights, InsightDetector detector) {
        return insights.stream().filter(i -> i.detector() == detector).findFirst().orElseThrow();
    }

    private WorkspaceId id() {
        return new WorkspaceId(UUID.fromString(workspace));
    }

    private String generatedAt() {
        return jdbc.queryForObject("select generated_at::text from insights_workspaces where workspace_id = ?::uuid",
                String.class, workspace);
    }

    private String createAccount() throws Exception {
        MvcTestResult result = mvc.post().uri(ACCOUNTS, workspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
    }

    private String create(String type, String day, String amount, String description) throws Exception {
        MvcTestResult result = mvc.post().uri(TRANSACTIONS, workspace).with(ana())
                .header("Idempotency-Key", "i-" + ++keys).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "%s", "description": "%s"}
                        """.formatted(type, checking, amount, day, description)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id").toString();
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
