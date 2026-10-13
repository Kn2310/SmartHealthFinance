package com.smarthealthfinance.analytics.application.service;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.enums.ComparisonBase;
import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.model.ComparisonPlan;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Monta o {@link AnalysisContext} de uma consulta (ADR-0015 §1/§2): autoriza o membro antes de validar qualquer
 * parâmetro (ADR-0003), resolve o mês no fuso de negócio (o mesmo do Overview, ADR-0006 §10), as bases de
 * comparação e carrega as linhas do fluxo de caixa numa única leitura da projeção.
 */
@Service
public class AnalysisContextLoader {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final AnalyticsReadModel readModel;
    private final Clock clock;
    private final ZoneId businessZone;

    public AnalysisContextLoader(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                                 AnalyticsReadModel readModel, Clock clock,
                                 @Value("${shf.overview.zone:America/Sao_Paulo}") ZoneId businessZone) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.readModel = readModel;
        this.clock = clock;
        this.businessZone = businessZone;
    }

    /**
     * @param month         {@code YYYY-MM}; nulo = mês corrente
     * @param compareWith   {@code PREVIOUS} (padrão) ou {@code AVERAGE}
     * @param averageMonths meses da média, 1–12 (padrão 5)
     */
    public record Query(String month, String compareWith, Integer averageMonths) {

        public static Query defaults() {
            return new Query(null, null, null);
        }
    }

    /** @param historyMonths meses (até o período, inclusive) que a consulta lê além das bases */
    public AnalysisContext load(UUID workspaceId, Query query, int historyMonths) {
        Workspace workspace = accessGuard.loadAsMember(currentUser.requireCurrentUserId(), new WorkspaceId(workspaceId));
        WorkspaceId id = workspace.id();

        AnalysisPeriod period = AnalysisPeriod.resolve(query.month(), LocalDate.now(clock.withZone(businessZone)));
        ComparisonBase base = ComparisonPlan.parseBase(query.compareWith());
        Optional<LocalDate> firstActivity = readModel.firstActivity(id);
        ComparisonPlan plan = ComparisonPlan.of(period, base, query.averageMonths(), firstActivity);

        LocalDate historyStart = period.month().minusMonths(Math.max(historyMonths, 1) - 1L).atDay(1);
        LocalDate from = plan.earliestDay(historyStart.isBefore(period.from()) ? historyStart : period.from());
        List<FlowEntry> entries = firstActivity.isEmpty() ? List.of()
                : readModel.entries(id, new AnalyticsPeriod(from, period.to()), workspace.baseCurrency());
        FlowLedger ledger = new FlowLedger(entries, workspace.baseCurrency());

        DataState state = firstActivity.isEmpty() ? DataState.NO_DATA
                : ledger.isEmpty(period.range()) ? DataState.NO_ACTIVITY_IN_PERIOD : DataState.READY;

        return new AnalysisContext(id, workspace.baseCurrency(), period, plan, ledger, readModel.freshness(id), state);
    }
}
