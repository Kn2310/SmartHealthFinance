package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.goals.domain.service.MonthlyAverages;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Médias de despesas e receitas na janela do Health (ADR-0036), lidas da porta do Analytics: o alvo sugerido da
 * reserva e o peso na renda.
 */
@Service
public class GoalAverages {

    private final AnalyticsReadModel analytics;
    private final GoalCalendar calendar;

    public GoalAverages(AnalyticsReadModel analytics, GoalCalendar calendar) {
        this.analytics = analytics;
        this.calendar = calendar;
    }

    public MonthlyAverages.Averages load(WorkspaceId workspace) {
        List<YearMonth> window = MonthlyAverages.window(calendar.today(),
                analytics.firstActivity(workspace));
        Optional<AnalyticsPeriod> range = MonthlyAverages.range(window);
        if (range.isEmpty()) {
            return MonthlyAverages.of(window, List.of(), GoalPlan.CURRENCY);
        }
        return MonthlyAverages.of(window, analytics.entries(workspace, range.get(), GoalPlan.CURRENCY),
                GoalPlan.CURRENCY);
    }

    /** Os códigos de categoria do catálogo, para validar a redução simulada. */
    public boolean isCategory(String code) {
        return code != null && analytics.categoryNames().containsKey(code);
    }
}
