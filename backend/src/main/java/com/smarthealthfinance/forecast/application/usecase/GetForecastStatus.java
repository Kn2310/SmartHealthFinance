package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.forecast.application.port.ForecastReader;
import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.service.ForecastInputLoader;
import com.smarthealthfinance.forecast.domain.model.ForecastFreshness;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Freshness do Forecast do Workspace (ADR-0031 §9). Autoriza o membro antes de ler. Um snapshot de um dia anterior
 * (o job diário ainda não rodou) marca o Workspace para recálculo e responde {@code RECALCULATING} (§6).
 */
@Service
public class GetForecastStatus {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final ForecastSnapshotStore store;
    private final AnalyticsReadModel analytics;
    private final ForecastInputLoader loader;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;

    public GetForecastStatus(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                             ForecastSnapshotStore store, AnalyticsReadModel analytics, ForecastInputLoader loader,
                             Clock clock, @Value("${shf.forecast.debounce.quiet:10s}") Duration quiet,
                             @Value("${shf.forecast.debounce.max-wait:60s}") Duration maxWait) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.store = store;
        this.analytics = analytics;
        this.loader = loader;
        this.clock = clock;
        this.quiet = quiet;
        this.maxWait = maxWait;
    }

    @Transactional
    public ForecastFreshness execute(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return freshness(id);
    }

    /** Também usado pelas consultas do Forecast (parte C); exige transação de escrita (pode marcar). */
    public ForecastFreshness freshness(WorkspaceId id) {
        ForecastReader.SnapshotState state = store.state(id, ForecastCalculator.ALGORITHM_VERSION);
        ProjectionFreshness projection = analytics.freshness(id);
        LocalDate today = loader.today();
        boolean stale = state.built() && state.latestAsOf() != null && state.latestAsOf().isBefore(today);
        if (stale && !state.dirty()) {
            store.markDirty(id, clock.instant(), quiet, maxWait);
        }
        return ForecastFreshness.resolve(state.built(), state.hasTransactions(), state.dirty() || stale,
                state.pendingEvents(), projection.state() != ProjectionState.UP_TO_DATE, state.latestAsOf(), today,
                state.computedAt(), projection.refreshedAt());
    }
}
