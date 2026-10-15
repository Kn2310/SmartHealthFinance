package com.smarthealthfinance.health.infrastructure.persistence.adapter;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.EvidenceKind;
import com.smarthealthfinance.health.domain.enums.EvidenceSource;
import com.smarthealthfinance.health.domain.enums.FactorDirection;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.HealthState;
import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.Factor;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.model.NextStep;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Debounce, {@code SKIP LOCKED}, versões e ida e volta exata do JSONB (ADR-0018 §3/§4). O job real roda junto, então
 * as marcas usam instantes no futuro distante para nunca vencer durante o teste.
 */
class JdbcHealthSnapshotStoreIT extends IntegrationTest {

    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Duration QUIET = Duration.ofSeconds(10);
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);
    private static final Currency BRL = Currency.getInstance("BRL");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HealthSnapshotStore store;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private WorkspaceId workspace;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        tx = new TransactionTemplate(transactionManager);
        MvcTestResult me = mvc.post().uri("/api/v1/users/me").with(jwt().jwt(token -> token.subject("sub-ana")
                .claim("iss", ISSUER).claim("email", "ana@example.com").claim("name", "Nome"))).exchange();
        workspace = new WorkspaceId(UUID.fromString(JsonPath.read(
                me.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.workspaceId")));
    }

    /** Marcas no futuro nunca vencem: sem limpar, a espera de quiescência da próxima classe esgotaria o prazo. */
    @AfterEach
    void clearMarks() {
        jdbc.update("delete from health_workspaces where workspace_id = ?", workspace.value());
    }

    @Test
    void eachMarkPostponesTheRecalculationButNeverBeyondTheCap() {
        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT));
        assertThat(due()).isEqualTo(FUTURE.plus(QUIET));
        assertThat(dirtySince()).isEqualTo(FUTURE);

        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE.plusSeconds(30), QUIET, MAX_WAIT));
        assertThat(due()).isEqualTo(FUTURE.plusSeconds(40));

        // Teto: dirty_since + 60 s, mesmo com eventos chegando sem parar.
        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE.plusSeconds(55), QUIET, MAX_WAIT));
        assertThat(due()).isEqualTo(FUTURE.plusSeconds(60));
        assertThat(dirtySince()).isEqualTo(FUTURE);
    }

    @Test
    void replaceClearsTheMarkAndKeepsOtherVersions() {
        HealthResult v1 = result("health-v1", YearMonth.of(2026, 9));
        HealthResult old = result("health-v0", YearMonth.of(2026, 9));
        tx.executeWithoutResult(s -> {
            store.replace(workspace, "health-v0", List.of(old), FUTURE);
            store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT);
            store.replace(workspace, "health-v1", List.of(v1, result("health-v1", YearMonth.of(2026, 8))), FUTURE);
            store.replace(workspace, "health-v1", List.of(v1), FUTURE.plusSeconds(1));
        });

        assertThat(jdbc.queryForObject("select count(*) from health_snapshots where workspace_id = ?",
                Integer.class, workspace.value())).isEqualTo(2);
        assertThat(dirtySince()).isNull();
        assertThat(due()).isNull();
        // Ida e volta exata, inclusive escalas dos decimais.
        assertThat(find("health-v1", YearMonth.of(2026, 9))).contains(v1);
        assertThat(find("health-v0", YearMonth.of(2026, 9))).contains(old);
        assertThat(find("health-v1", YearMonth.of(2026, 8))).isEmpty();
        // Dinheiro e razões viram string no JSONB, nunca número.
        assertThat(jdbc.queryForObject("""
                select jsonb_typeof(result #> '{nextSteps,0,amount}') || ' ' || jsonb_typeof(result -> 'uncategorizedShare')
                from health_snapshots where workspace_id = ? and algorithm_version = 'health-v1'
                """, String.class, workspace.value())).isEqualTo("string string");

        HealthSnapshotStore.SnapshotState state = tx.execute(s -> store.state(workspace, "health-v1"));
        assertThat(state.built()).isTrue();
        assertThat(state.dirty()).isFalse();
        assertThat(state.latestAsOf()).isEqualTo(LocalDate.parse("2026-09-22"));
        assertThat(state.computedAt()).isEqualTo(FUTURE.plusSeconds(1));
    }

    /** Duas instâncias nunca recalculam o mesmo Workspace: a segunda não vê o vencido que está bloqueado. */
    @Test
    void claimSkipsWorkspacesLockedByAnotherInstance() throws Exception {
        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT));
        Instant later = FUTURE.plus(Duration.ofMinutes(5));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<WorkspaceId>> first = pool.submit(() -> tx.execute(s -> {
                Optional<WorkspaceId> due = store.claimDue(later);
                claimed.countDown();
                await(release);
                return due;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            Optional<WorkspaceId> second = tx.execute(s -> store.claimDue(later));
            assertThat(second).isEmpty();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(workspace);
        }
        finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Optional<HealthResult> find(String version, YearMonth month) {
        return tx.execute(s -> store.find(workspace, version, month));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Instant due() {
        return jdbc.queryForObject("select due_at from health_workspaces where workspace_id = ?",
                (rs, row) -> rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant(),
                workspace.value());
    }

    private Instant dirtySince() {
        return jdbc.queryForObject("select dirty_since from health_workspaces where workspace_id = ?",
                (rs, row) -> rs.getTimestamp("dirty_since") == null ? null
                        : rs.getTimestamp("dirty_since").toInstant(), workspace.value());
    }

    private static HealthResult result(String version, YearMonth month) {
        LocalDate asOf = month.atDay(22);
        DimensionResult reserve = DimensionResult.ok(HealthDimension.RESERVE, 69, "Sua reserva cobre ≈ 2,4 meses.",
                true, List.of(new Evidence("coverageMonths", "Meses de cobertura", EvidenceKind.MONTHS,
                        new BigDecimal("2.39"), month.atDay(1), asOf, EvidenceSource.OVERVIEW_ACCOUNT_BALANCES),
                        new Evidence("reserveBalance", "Saldo", EvidenceKind.MONEY, new BigDecimal("10000.0000"), asOf,
                                asOf, EvidenceSource.OVERVIEW_ACCOUNT_BALANCES)));
        DimensionResult cashFlow = DimensionResult.ok(HealthDimension.CASH_FLOW, 97, "Receitas cobriram.", false,
                List.of());
        return new HealthResult(version, month, asOf, true, month.minusMonths(5).atDay(1),
                month.minusMonths(1).atEndOfMonth(), 5, HealthState.READY, 84, 3, false, Confidence.MEDIUM,
                new BigDecimal("12.5"), new HealthResult.Coverage(2, 6, 45), 3, "Resumo.",
                List.of(cashFlow, DimensionResult.notApplicable(HealthDimension.CREDIT), reserve),
                List.of(new Factor(HealthDimension.RESERVE, FactorDirection.WEIGHING, "Sua reserva cobre ≈ 2,4 meses.")),
                List.of(new NextStep(NextStepKind.BUILD_RESERVE, "Fortalecer a reserva", "Faltam R$ 15.152,00.",
                        null, null, Money.of("15152.00", BRL)), new NextStep(NextStepKind.REVIEW_CATEGORY,
                        "Alimentação fora 32,0% acima da média", "+R$ 311,00.", null, month, null)));
    }
}
