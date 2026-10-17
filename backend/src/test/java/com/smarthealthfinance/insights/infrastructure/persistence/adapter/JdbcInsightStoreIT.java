package com.smarthealthfinance.insights.infrastructure.persistence.adapter;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightFeedback;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.enums.StatusReason;
import com.smarthealthfinance.insights.domain.model.ChangeLine;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.insights.domain.model.Recommendation;
import com.smarthealthfinance.insights.domain.model.SeriesPoint;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.insights.domain.service.InsightFingerprint;
import com.smarthealthfinance.insights.domain.service.InsightRules;
import com.smarthealthfinance.insights.domain.service.InsightScoring;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Ida e volta exata do insight e do JSONB, upsert por fingerprint, estado, rebuild e retenção (ADR-0022 §5–§8). O job
 * real roda junto, então as marcas usam instantes no futuro distante para nunca vencer durante o teste.
 */
class JdbcInsightStoreIT extends IntegrationTest {

    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Currency BRL = Currency.getInstance("BRL");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InsightStore store;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private WorkspaceId workspace;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        tx = new TransactionTemplate(transactionManager);
        MvcTestResult me = mvc.post().uri("/api/v1/users/me").with(ana()).exchange();
        workspace = new WorkspaceId(UUID.fromString(JsonPath.read(
                me.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.workspaceId")));
    }

    /** Marcas no futuro nunca vencem: sem limpar, a espera de quiescência da próxima classe esgotaria o prazo. */
    @AfterEach
    void clearMarks() {
        jdbc.update("delete from insights_workspaces where workspace_id = ?", workspace.value());
    }

    private Insight insight(String period, String subject, InsightStatus status, Instant updatedAt) {
        UUID transaction = UUID.randomUUID();
        InsightContent content = new InsightContent("Seus gastos com alimentação fora aumentaram 32% neste mês.",
                "R$ 1.284,00 até 22 de setembro.", "Alimentação fora",
                new ChangeLine("Alimentação fora", "R$ 1.284,00 até 22 set", "+32% vs média de abr–ago"),
                new Recommendation("Revisar ENTREGA JA", "35% do valor."),
                new InsightEvidence(
                        List.of(new KeyFigure("current", "Setembro (1–22)", FigureKind.MONEY,
                                        new BigDecimal("1284.0000"), LocalDate.parse("2026-09-01"),
                                        LocalDate.parse("2026-09-22")),
                                new KeyFigure("percent", "Variação", FigureKind.PERCENT, new BigDecimal("32.0"),
                                        LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-22"))),
                        List.of(new SeriesPoint(YearMonth.of(2026, 8), new BigDecimal("980.0000"), false),
                                new SeriesPoint(YearMonth.of(2026, 9), new BigDecimal("1284.0000"), true)),
                        List.of(new Contributor(ContributorKind.MERCHANT, "entrega ja", "ENTREGA JA",
                                        new BigDecimal("447.0000"), 9, new BigDecimal("34.8"), null),
                                new Contributor(ContributorKind.TRANSACTION, transaction.toString(),
                                        "Comerciante não identificado", new BigDecimal("86.4000"), 1, null,
                                        LocalDate.parse("2026-09-22"))),
                        "Comparamos…",
                        List.of(new Limitation(LimitationCode.PARTIAL_MONTH, "Setembro ainda não terminou.")),
                        List.of(new SourceRef(InsightSource.ANALYTICS_CATEGORIES, LocalDate.parse("2026-04-01"),
                                LocalDate.parse("2026-09-22")))),
                List.of(InsightAction.primary(ActionKind.VIEW_TRANSACTIONS, "Ver as 25 transações",
                                Map.of("from", "2026-09-01", "to", "2026-09-22", "category", "DINING_OUT")),
                        InsightAction.comingSoon(ActionKind.ASK_COPILOT, "Perguntar ao Copilot")),
                new InsightRefs("DINING_OUT", "entrega ja", null, transaction, HealthDimension.SPENDING));
        InsightCandidate candidate = new InsightCandidate(InsightDetector.CATEGORY_SPIKE, period, subject,
                Money.of("311.00", BRL), null, true, Confidence.HIGH, true, content);
        Insight created = Insight.create(UUID.randomUUID(), workspace.value(), InsightRules.ALGORITHM_VERSION,
                InsightScoring.score(candidate, InsightFingerprint.of(candidate.detector(), workspace.value(),
                        period, subject), Money.of("4192.00", BRL), List.of(), NOW), updatedAt);
        return status == InsightStatus.ACTIVE ? created : created.withStatus(status, StatusReason.PERIOD_ENDED,
                updatedAt);
    }

    private List<Insight> all() {
        return tx.execute(s -> store.findAll(workspace));
    }

    private InsightStore.GenerationState state(String version) {
        return tx.execute(s -> store.state(workspace, version));
    }

    private int rebuild(String version, boolean all) {
        Integer marked = tx.execute(s -> store.markForRebuild(version, all, FUTURE));
        return marked == null ? -1 : marked;
    }

    private int purge(Instant before) {
        Integer purged = tx.execute(s -> store.purgeHiddenBefore(before));
        return purged == null ? -1 : purged;
    }

    @Test
    void roundTripIsExact() {
        Insight original = insight("2026-09", "DINING_OUT", InsightStatus.ACTIVE, NOW).withSeenAt(NOW);
        Insight withFeedback = new Insight(original.id(), original.workspaceId(), original.fingerprint(),
                original.algorithmVersion(), original.detector(), original.periodKey(), original.subject(),
                original.status(), null, original.importance(), original.impact(), original.relevance(),
                original.confidence(), original.priority(), original.impactAmount(), null, true, original.content(),
                NOW, null, InsightFeedback.USEFUL, NOW, 1, NOW, NOW, NOW);
        tx.executeWithoutResult(s -> store.save(List.of(withFeedback)));

        assertThat(all()).containsExactly(withFeedback);
        assertThat(jdbc.queryForObject("select content->'evidence'->'figures'->0->>'value' from insights",
                String.class)).isEqualTo("1284.0000");
        assertThat(jdbc.queryForObject("select jsonb_typeof(content->'evidence'->'figures'->0->'value') from insights",
                String.class)).isEqualTo("string");
    }

    @Test
    void upsertUpdatesTheSameRow() {
        Insight original = insight("2026-09", "DINING_OUT", InsightStatus.ACTIVE, NOW);
        tx.executeWithoutResult(s -> store.save(List.of(original)));
        Insight resolved = original.withStatus(InsightStatus.RESOLVED, null, NOW.plusSeconds(5));
        tx.executeWithoutResult(s -> store.save(List.of(resolved)));

        assertThat(all()).containsExactly(resolved);
    }

    @Test
    void stateAndRebuildFollowTheLastGeneration() throws Exception {
        assertThat(state("insights-v1"))
                .isEqualTo(new InsightStore.GenerationState(false, false, null, null, false, 0));
        createTransaction();
        IdentityTables.awaitQuiescence(jdbc);

        InsightStore.GenerationState state = state("insights-v1");
        assertThat(state.built()).isTrue();
        assertThat(state.hasTransactions()).isTrue();
        assertThat(state.asOf()).isNotNull();
        assertThat(state("insights-v2").built()).isFalse();

        // missing: já gerado na versão atual; all: todos com transações.
        assertThat(rebuild("insights-v1", false)).isZero();
        assertThat(rebuild("insights-v2", false)).isEqualTo(1);
        assertThat(rebuild("insights-v1", true)).isEqualTo(1);
        assertThat(state("insights-v1").dirty()).isTrue();
    }

    @Test
    void retentionDeletesOnlyOldHiddenInsights() {
        Instant old = NOW.minus(Duration.ofDays(500));
        Insight oldExpired = insight("2025-04", "A", InsightStatus.EXPIRED, old);
        Insight oldActive = insight("2025-04", "B", InsightStatus.ACTIVE, old);
        Insight recentExpired = insight("2026-08", "C", InsightStatus.EXPIRED, NOW);
        tx.executeWithoutResult(s -> store.save(List.of(oldExpired, oldActive, recentExpired)));

        assertThat(purge(NOW.minus(Duration.ofDays(395)))).isEqualTo(1);
        assertThat(all()).extracting(Insight::subject).containsExactlyInAnyOrder("B", "C");
    }

    private void createTransaction() throws Exception {
        MvcTestResult account = mvc.post().uri("/api/v1/workspaces/{id}/accounts", workspace.value()).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Banco Aurora\", \"type\": \"CHECKING\"}")
                .exchange();
        String accountId = JsonPath.read(account.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
        assertThat(mvc.post().uri("/api/v1/workspaces/{id}/transactions", workspace.value()).with(ana())
                .header("Idempotency-Key", "s-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "10.00", "currency": "BRL"},
                         "occurredOn": "%s", "description": "Padaria"}
                        """.formatted(accountId, LocalDate.now().minusDays(1))).exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(token -> token.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Nome"));
    }
}
