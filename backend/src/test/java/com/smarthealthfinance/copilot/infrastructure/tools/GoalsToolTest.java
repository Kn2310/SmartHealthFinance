package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.goals.application.dto.GoalListView;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.usecase.GetGoal;
import com.smarthealthfinance.goals.application.usecase.GetGoalProjection;
import com.smarthealthfinance.goals.application.usecase.ListGoals;
import com.smarthealthfinance.goals.domain.enums.DestinationKind;
import com.smarthealthfinance.goals.domain.enums.DestinationState;
import com.smarthealthfinance.goals.domain.enums.GoalConfidence;
import com.smarthealthfinance.goals.domain.enums.GoalForecastFreshness;
import com.smarthealthfinance.goals.domain.enums.GoalForecastStatus;
import com.smarthealthfinance.goals.domain.enums.GoalOriginKind;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import com.smarthealthfinance.goals.domain.valueobject.GoalProgress;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.WORKSPACE;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.assertNoLeak;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.brl;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.decision;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.request;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalsToolTest {

    private static final String GOAL_NAME = "Viagem para Lisboa dos sonhos";
    private static final UUID GOAL_ID = UUID.fromString("9a8b7c6d-1111-4222-8333-444455556666");

    private final ListGoals list = mock(ListGoals.class);
    private final GetGoal get = mock(GetGoal.class);
    private final GetGoalProjection projection = mock(GetGoalProjection.class);
    private final GoalsTool tool = new GoalsTool(list, get, projection);

    private static GoalView goal(UUID id, GoalType type, String name) {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        var forecast = new GoalForecast(GoalForecastStatus.ON_TRACK, null, YearMonth.of(2027, 8), 9, null, 1, null,
                false, GoalConfidence.MEDIUM, brl("500"));
        return new GoalView(id, name, type, GoalStatus.ACTIVE, GoalOriginKind.MANUAL, null, brl("10000"),
                brl("500"), YearMonth.of(2027, 9), null, DestinationKind.RESERVE, null, LocalDate.of(2026, 1, 1),
                now, null, null, new GoalProgress(brl("4500"), 45, brl("5500"), false, brl("4500")),
                DestinationState.OK, forecast, GoalForecastFreshness.RECALCULATING);
    }

    private static GoalListView listView(GoalView... goals) {
        return new GoalListView(List.of(goals), brl("4500"), brl("500"), goals.length, 12, 1, 1,
                GoalForecastFreshness.RECALCULATING);
    }

    @Test
    void listsGoalsByPseudonymWithoutTheirNames() {
        when(list.execute(WORKSPACE, null)).thenReturn(listView(goal(GOAL_ID, GoalType.PURCHASE, GOAL_NAME),
                goal(UUID.randomUUID(), GoalType.EMERGENCY_RESERVE, "Colchão")));
        Pseudonyms pseudonyms = new Pseudonyms();

        var result = tool.collect(request(IntentDecision.of(Intent.GOALS), pseudonyms));

        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Total guardado nos objetivos abertos").value()).isEqualTo("4500");
        assertThat(value(result, "Aportes planejados sobre a renda média mensal").value()).isEqualTo("12");
        assertThat(value(result, "Meta de objetivo_1 (compra)").value()).isEqualTo("10000");
        assertThat(value(result, "Guardado em objetivo_1 (compra)").value()).isEqualTo("4500");
        assertThat(value(result, "Falta para a meta de objetivo_1 (compra)").value()).isEqualTo("5500");
        assertThat(value(result, "Progresso de objetivo_1 (compra)").value()).isEqualTo("45");
        assertThat(value(result, "Aporte mensal planejado de objetivo_1 (compra)").value()).isEqualTo("500");
        assertThat(value(result, "Prazo de objetivo_1 (compra)").value()).isEqualTo("2027-09");
        assertThat(value(result, "Previsão de conclusão de objetivo_1 (compra)").kind())
                .isEqualTo(ValueKind.ESTIMATED);
        assertThat(value(result, "Meta de objetivo_2 (reserva de emergência)").value()).isEqualTo("10000");
        assertThat(result.quality().freshness()).isEqualTo(DataFreshness.UPDATING);
        assertThat(result.actions()).containsExactly(new OfferedAction(ActionKind.OPEN_GOAL, "objetivo_1"),
                new OfferedAction(ActionKind.OPEN_GOAL, "objetivo_2"));
        verify(get, never()).execute(any(), any());
        assertNoLeak(result, GOAL_NAME, "Colchão", GOAL_ID.toString());
    }

    @Test
    void limitsToFiveGoals() {
        var goals = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> goal(UUID.randomUUID(), GoalType.CUSTOM, "Meta")).toArray(GoalView[]::new);
        when(list.execute(WORKSPACE, null)).thenReturn(listView(goals));

        var result = tool.collect(request(Intent.GOALS));

        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Meta de ")).hasSize(5);
    }

    @Test
    void detailsTheTargetGoalWithActualAndProjectedSeries() {
        GoalView goal = goal(GOAL_ID, GoalType.PURCHASE, GOAL_NAME);
        when(get.execute(WORKSPACE, GOAL_ID)).thenReturn(goal);
        when(projection.execute(WORKSPACE, GOAL_ID)).thenReturn(new GetGoalProjection.Projection(goal,
                List.of(new GetGoalProjection.Point(YearMonth.of(2026, 7), brl("2000")),
                        new GetGoalProjection.Point(YearMonth.of(2026, 8), brl("2500")),
                        new GetGoalProjection.Point(YearMonth.of(2026, 9), brl("3000")),
                        new GetGoalProjection.Point(YearMonth.of(2026, 10), brl("3600")),
                        new GetGoalProjection.Point(YearMonth.of(2026, 11), brl("4500"))),
                List.of(new GetGoalProjection.Point(YearMonth.of(2026, 11), brl("4500")),
                        new GetGoalProjection.Point(YearMonth.of(2027, 8), brl("10000")))));
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.GOAL, GOAL_ID.toString(), "PURCHASE", GOAL_NAME);

        var result = tool.collect(request(decision(Intent.GOALS, null, null, "objetivo_1", null), pseudonyms));

        verify(list, never()).execute(any(), any());
        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Guardado no fim do mês em "))
                .extracting(v -> v.period()).containsExactly("2026-09", "2026-10", "2026-11");
        var projected = value(result, "Valor projetado na conclusão de objetivo_1 (compra)");
        assertThat(projected.value()).isEqualTo("10000");
        assertThat(projected.kind()).isEqualTo(ValueKind.ESTIMATED);
        assertThat(projected.period()).isEqualTo("2027-08");
        assertNoLeak(result, GOAL_NAME, GOAL_ID.toString());
    }

    @Test
    void reportsNoDataWithoutGoals() {
        when(list.execute(WORKSPACE, null)).thenReturn(listView());

        assertThat(tool.collect(request(Intent.GOALS)).status()).isEqualTo(FinancialTool.Status.NO_DATA);
    }
}
