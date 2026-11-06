package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.service.SimulationEngine;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dados do gráfico do D-GoalDetail (ADR-0036): o guardado real no fim de cada mês desde o início do acompanhamento
 * (fato; o mês corrente vai até hoje), a projeção pelo plano até a conclusão (estimado, só com previsão) e o prazo.
 * Tudo calculado aqui; o frontend só desenha.
 */
@Service
public class GetGoalProjection {

    /** Meses de histórico real no gráfico. */
    public static final int MAX_ACTUAL_MONTHS = 36;

    private final GoalAccess access;
    private final GoalReader reader;

    public GetGoalProjection(GoalAccess access, GoalReader reader) {
        this.access = access;
        this.reader = reader;
    }

    public record Point(YearMonth month, Money amount) {}

    /**
     * @param actual    {@code FACT}, do mês do início (ou dos últimos 36) até o corrente; nunca negativo
     * @param projected {@code ESTIMATED}, do mês corrente (o guardado hoje) até a conclusão; vazio sem previsão
     */
    public record Projection(GoalView goal, List<Point> actual, List<Point> projected) {}

    @Transactional(readOnly = true)
    public Projection execute(UUID workspaceId, UUID goalId) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Goal goal = access.requireGoal(workspace, goalId);
        GoalView view = reader.view(workspace, goal);
        LocalDate today = reader.today();
        YearMonth current = YearMonth.from(today);

        List<Point> actual = new ArrayList<>();
        Set<AccountId> accounts = reader.destinationAccounts(workspace, goal);
        YearMonth first = YearMonth.from(goal.trackingStart());
        if (first.isBefore(current.minusMonths(MAX_ACTUAL_MONTHS - 1L))) {
            first = current.minusMonths(MAX_ACTUAL_MONTHS - 1L);
        }
        for (YearMonth month = first; !month.isAfter(current); month = month.plusMonths(1)) {
            LocalDate end = month.equals(current) ? today : month.atEndOfMonth();
            Money balance = reader.balanceAt(workspace, accounts, end);
            actual.add(new Point(month, balance.amount().signum() < 0 ? Money.zero(GoalPlan.CURRENCY) : balance));
        }

        List<Point> projected = new ArrayList<>();
        GoalForecast forecast = view.forecast();
        if (forecast.hasCompletion()) {
            Money saved = view.progress().saved();
            Money target = view.targetAmount();
            projected.add(new Point(current, saved));
            YearMonth month = SimulationEngine.start(current);
            for (int k = 1; !month.isAfter(forecast.completion()); k++, month = month.plusMonths(1)) {
                BigDecimal value = saved.amount().add(view.plannedMonthly().amount().multiply(BigDecimal.valueOf(k)));
                projected.add(new Point(month, new Money(value.min(target.amount()), GoalPlan.CURRENCY)));
            }
        }
        return new Projection(view, actual, projected);
    }
}
