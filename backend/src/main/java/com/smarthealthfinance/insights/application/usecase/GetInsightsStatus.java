package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.health.application.port.HealthSnapshotReader;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.service.InsightInputLoader;
import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import com.smarthealthfinance.insights.domain.service.InsightRules;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freshness dos insights do Workspace (ADR-0022 §8). Autoriza o membro antes de ler. Uma geração de um dia anterior
 * marca o Workspace para o job e responde {@code UPDATING}.
 */
@Service
public class GetInsightsStatus {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final InsightStore store;
    private final HealthSnapshotReader health;
    private final AnalyticsReadModel analytics;
    private final InsightInputLoader loader;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;

    public GetInsightsStatus(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, InsightStore store,
                             HealthSnapshotReader health, AnalyticsReadModel analytics, InsightInputLoader loader,
                             Clock clock, @Value("${shf.insights.debounce.quiet:10s}") Duration quiet,
                             @Value("${shf.insights.debounce.max-wait:60s}") Duration maxWait) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.store = store;
        this.health = health;
        this.analytics = analytics;
        this.loader = loader;
        this.clock = clock;
        this.quiet = quiet;
        this.maxWait = maxWait;
    }

    @Transactional
    public InsightsFreshness execute(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return freshness(id);
    }

    /** Também usado pelas consultas da parte C; exige transação de escrita (pode marcar). */
    public InsightsFreshness freshness(WorkspaceId id) {
        InsightStore.GenerationState state = store.state(id, InsightRules.ALGORITHM_VERSION);
        HealthSnapshotReader.SnapshotState snapshot = health.state(id, HealthCalculator.ALGORITHM_VERSION);
        LocalDate today = loader.today();
        boolean stale = state.built() && state.asOf() != null && state.asOf().isBefore(today);
        if (stale && !state.dirty()) {
            store.markDirty(id, clock.instant(), quiet, maxWait);
        }
        boolean healthUpdating = snapshot.dirty() || snapshot.pendingEvents() > 0
                || (snapshot.latestAsOf() != null && snapshot.latestAsOf().isBefore(today));
        boolean analyticsUpdating = analytics.freshness(id).state() != ProjectionState.UP_TO_DATE;
        return InsightsFreshness.resolve(state.built(), state.hasTransactions(),
                state.dirty() || stale || state.pendingEvents() > 0, healthUpdating || analyticsUpdating,
                state.asOf(), today, state.generatedAt(), snapshot.computedAt());
    }
}
