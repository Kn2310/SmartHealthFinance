package com.smarthealthfinance.health.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.service.HealthInputLoader;
import com.smarthealthfinance.health.domain.model.HealthFreshness;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freshness do Health do Workspace (ADR-0018 §7). Autoriza o membro antes de ler. Um snapshot de um dia anterior
 * (o job diário ainda não rodou) marca o Workspace para recálculo e responde {@code RECALCULATING} (§5).
 */
@Service
public class GetHealthStatus {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final HealthSnapshotStore store;
    private final AnalyticsReadModel analytics;
    private final HealthInputLoader loader;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;

    public GetHealthStatus(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                           HealthSnapshotStore store, AnalyticsReadModel analytics, HealthInputLoader loader,
                           Clock clock, @Value("${shf.health.debounce.quiet:10s}") Duration quiet,
                           @Value("${shf.health.debounce.max-wait:60s}") Duration maxWait) {
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
    public HealthFreshness execute(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return freshness(id);
    }

    /** Também usado pelas consultas do Health (parte D); exige transação de escrita (pode marcar). */
    public HealthFreshness freshness(WorkspaceId id) {
        HealthSnapshotStore.SnapshotState state = store.state(id, HealthCalculator.ALGORITHM_VERSION);
        ProjectionFreshness projection = analytics.freshness(id);
        LocalDate today = loader.today();
        boolean stale = state.built() && state.latestAsOf() != null && state.latestAsOf().isBefore(today);
        if (stale && !state.dirty()) {
            store.markDirty(id, clock.instant(), quiet, maxWait);
        }
        return HealthFreshness.resolve(state.built(), state.hasTransactions(), state.dirty() || stale,
                state.pendingEvents(), projection.state() != ProjectionState.UP_TO_DATE, state.latestAsOf(), today,
                state.computedAt(), projection.refreshedAt());
    }
}
