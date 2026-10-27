package com.smarthealthfinance.insights.application.service;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.cards.application.port.CardPortfolio;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.health.application.port.HealthSnapshotReader;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.insights.domain.service.InsightRules;
import com.smarthealthfinance.insights.domain.valueobject.EvaluationPeriod;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Monta a entrada dos detectores (ADR-0022 §4) só pelas portas: uma leitura das linhas do fluxo de caixa
 * ({@link AnalyticsReadModel#entries}) de P − 7 meses até hoje, o primeiro lançamento, o catálogo de categorias, as
 * maiores despesas de P, os snapshots do Health de P − 6 a P e a posição dos cartões hoje e no último dia de cada um
 * dos 6 meses anteriores ({@link CardPortfolio}, ADR-0028 §1). Nenhuma regra financeira própria.
 */
@Service
public class InsightInputLoader {

    /** Meses antes de P que os detectores leem (D3: série + ausência; D4/D6: 6 meses; D5: 6 meses até P − 1). */
    static final int LOOKBACK_MONTHS = 7;
    private static final int HEALTH_MONTHS = 6;
    /** Meses de fim de mês da série de utilização (D12). */
    private static final int CARD_MONTHS = 6;

    private final WorkspaceRepository workspaces;
    private final AnalyticsReadModel analytics;
    private final HealthSnapshotReader health;
    private final CardPortfolio cards;
    private final Clock clock;
    private final ZoneId businessZone;

    public InsightInputLoader(WorkspaceRepository workspaces, AnalyticsReadModel analytics,
                              HealthSnapshotReader health, CardPortfolio cards, Clock clock,
                              @Value("${shf.overview.zone:America/Sao_Paulo}") ZoneId businessZone) {
        this.workspaces = workspaces;
        this.analytics = analytics;
        this.health = health;
        this.cards = cards;
        this.clock = clock;
        this.businessZone = businessZone;
    }

    /** Hoje no fuso de negócio (ADR-0006 §10). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(businessZone));
    }

    public InsightInput load(WorkspaceId workspaceId) {
        Workspace workspace = workspaces.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException());
        Currency currency = workspace.baseCurrency();
        LocalDate today = today();
        EvaluationPeriod period = EvaluationPeriod.of(today);
        Optional<LocalDate> firstActivity = analytics.firstActivity(workspaceId);
        if (firstActivity.isEmpty()) {
            return new InsightInput(period, firstActivity, new FlowLedger(List.of(), currency), Map.of(), List.of(),
                    Map.of());
        }
        YearMonth p = period.month();
        List<FlowEntry> entries = analytics.entries(workspaceId,
                new AnalyticsPeriod(p.minusMonths(LOOKBACK_MONTHS).atDay(1), today), currency);
        Map<YearMonth, HealthResult> snapshots = health.findMonths(workspaceId, HealthCalculator.ALGORITHM_VERSION,
                        p.minusMonths(HEALTH_MONTHS), p).stream()
                .collect(Collectors.toMap(HealthResult::month, Function.identity()));
        List<LocalDate> cardDates = new ArrayList<>();
        cardDates.add(today);
        for (int back = 1; back <= CARD_MONTHS; back++) {
            cardDates.add(YearMonth.from(today).minusMonths(back).atEndOfMonth());
        }
        Map<LocalDate, List<CardPosition>> positions = cards.positions(workspaceId, cardDates);
        Map<YearMonth, List<CardPosition>> atMonthEnd = new HashMap<>();
        positions.forEach((date, list) -> {
            if (!date.equals(today)) {
                atMonthEnd.put(YearMonth.from(date), list);
            }
        });
        return new InsightInput(period, firstActivity, new FlowLedger(entries, currency), analytics.categoryNames(),
                analytics.largestExpenses(workspaceId, period.period().range(), InsightRules.UNUSUAL_CANDIDATES,
                        currency),
                snapshots, positions.getOrDefault(today, List.of()), atMonthEnd);
    }
}
