package com.smarthealthfinance.health.application.usecase;

import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.health.application.dto.HealthViews;
import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.service.HealthInputLoader;
import com.smarthealthfinance.health.domain.model.HealthFreshness;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.health.domain.service.HealthMethod;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas do Health (ADR-0019). Autorizam o membro antes de validar qualquer parâmetro (ADR-0003) e leem só o
 * snapshot gravado (ADR-0018): nenhum cálculo de score na requisição. A freshness pode marcar o Workspace para
 * recálculo (snapshot de um dia anterior), por isso as transações são de escrita.
 */
@Service
public class HealthQueries {

    public static final int DEFAULT_EVOLUTION_MONTHS = 6;
    public static final int MAX_EVOLUTION_MONTHS = HealthInputLoader.STORED_CLOSED_MONTHS;

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final HealthSnapshotStore store;
    private final GetHealthStatus status;
    private final HealthInputLoader loader;
    private final OverviewReadModel overview;
    private final MeterRegistry meters;

    public HealthQueries(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, HealthSnapshotStore store,
                         GetHealthStatus status, HealthInputLoader loader, OverviewReadModel overview,
                         MeterRegistry meters) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.store = store;
        this.status = status;
        this.loader = loader;
        this.overview = overview;
        this.meters = meters;
    }

    /** @param month {@code YYYY-MM}; nulo = mês corrente */
    @Transactional
    public HealthViews.Summary summary(UUID workspaceId, String month) {
        Timer.Sample sample = Timer.start(meters);
        Workspace workspace = member(workspaceId);
        YearMonth target = resolve(month);
        Optional<HealthResult> result = store.find(workspace.id(), HealthCalculator.ALGORITHM_VERSION, target);
        HealthFreshness freshness = status.freshness(workspace.id());
        Set<DataSource> sources = result.map(r -> sources(workspace.id(), r)).orElse(Set.of());
        sample.stop(Timer.builder("shf.health.query").tag("view", "summary")
                .tag("state", result.map(r -> r.state().name()).orElse("NO_DATA")).register(meters));
        return new HealthViews.Summary(target, result.orElse(null), freshness, sources, workspace.baseCurrency());
    }

    /**
     * @param month  último mês da série ({@code YYYY-MM}); nulo = mês corrente
     * @param months 1–12 (padrão 6)
     */
    @Transactional
    public HealthViews.Evolution evolution(UUID workspaceId, String month, Integer months) {
        Workspace workspace = member(workspaceId);
        YearMonth to = resolve(month);
        int count = months == null ? DEFAULT_EVOLUTION_MONTHS : months;
        if (count < 1 || count > MAX_EVOLUTION_MONTHS) {
            throw new InvalidValueException("months", "OUT_OF_RANGE");
        }
        YearMonth from = to.minusMonths(count - 1L);
        Map<YearMonth, HealthResult> stored = store.findMonths(workspace.id(), HealthCalculator.ALGORITHM_VERSION,
                from, to).stream().collect(Collectors.toMap(HealthResult::month, Function.identity()));
        List<HealthViews.Month> series = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            series.add(new HealthViews.Month(m, stored.get(m)));
        }
        return new HealthViews.Evolution(to, series, status.freshness(workspace.id()));
    }

    @Transactional(readOnly = true)
    public HealthMethod.Description method(UUID workspaceId) {
        member(workspaceId);
        return HealthMethod.describe();
    }

    private Workspace member(UUID workspaceId) {
        return accessGuard.loadAsMember(currentUser.requireCurrentUserId(), new WorkspaceId(workspaceId));
    }

    /** Mesma validação da Análise: {@code month} INVALID ou FUTURE. */
    private YearMonth resolve(String month) {
        return AnalysisPeriod.resolve(month, loader.today()).month();
    }

    /** Da janela até a data-base; sem janela (nenhum mês fechado), só o mês de referência. */
    private Set<DataSource> sources(WorkspaceId workspaceId, HealthResult result) {
        LocalDate from = result.windowFrom() == null ? result.month().atDay(1) : result.windowFrom();
        return overview.dataSources(workspaceId, OverviewPeriod.resolve(PeriodType.CUSTOM, from, result.asOf(),
                result.asOf()));
    }
}
