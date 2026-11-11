package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.goals.application.dto.SimulationView;
import com.smarthealthfinance.goals.application.service.GoalSimulator;
import com.smarthealthfinance.goals.application.usecase.SimulateGoal;
import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import org.junit.jupiter.api.Test;

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

class GoalSimulationToolTest {

    private static final String GOAL_NAME = "Viagem para Lisboa dos sonhos";
    private static final UUID GOAL_ID = UUID.fromString("9a8b7c6d-1111-4222-8333-444455556666");
    private static final IntentDecision.Scenario SCENARIO = new IntentDecision.Scenario("800", "2027-06", "FOOD",
            "150");

    private final SimulateGoal simulateGoal = mock(SimulateGoal.class);
    private final GoalSimulationTool tool = new GoalSimulationTool(simulateGoal);

    private static Pseudonyms pseudonyms() {
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.GOAL, GOAL_ID.toString(), "PURCHASE", GOAL_NAME);
        return pseudonyms;
    }

    @Test
    void simulatesTheScenarioAndLabelsEveryResultAsSimulated() {
        var scenario = new SimulationScenario(brl("950"), YearMonth.of(2027, 5), 6, YearMonth.of(2026, 12),
                brl("850"), YearMonth.of(2027, 6), true, -1, brl("920"), 18, false);
        when(simulateGoal.execute(WORKSPACE, GOAL_ID,
                List.of(new GoalSimulator.Scenario("800", "2027-06", "FOOD", "150")))).thenReturn(
                new SimulationView(brl("5500"), YearMonth.of(2026, 12), brl("5000"),
                        List.of(new SimulationView.ScenarioView(scenario, "FOOD", brl("150")))));

        var result = tool.collect(request(decision(Intent.GOAL_SIMULATION, null, null, "objetivo_1", SCENARIO),
                pseudonyms()));

        String tag = "objetivo_1 (compra)";
        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Falta para a meta de " + tag).kind()).isEqualTo(ValueKind.FACT);
        assertThat(value(result, "Falta para a meta de " + tag).value()).isEqualTo("5500");
        assertThat(value(result, "Aporte mensal simulado para " + tag).value()).isEqualTo("950");
        assertThat(value(result, "Redução mensal de gastos simulada para " + tag).value()).isEqualTo("150");
        assertThat(value(result, "Categoria da redução simulada para " + tag).value()).isEqualTo("Alimentação");
        assertThat(value(result, "Conclusão simulada de " + tag).value()).isEqualTo("2027-05");
        assertThat(value(result, "Aportes necessários na simulação para " + tag).value()).isEqualTo("6");
        assertThat(value(result, "Meses entre a conclusão simulada e o prazo de " + tag + ", negativo se antes")
                .value()).isEqualTo("-1");
        assertThat(value(result, "Cabe no prazo de " + tag + " na simulação").value()).isEqualTo("sim");
        assertThat(value(result, "Aporte simulado sobre a renda média mensal").value()).isEqualTo("18");
        assertThat(result.values().stream().filter(v -> !v.label().startsWith("Falta para a meta")))
                .isNotEmpty().allMatch(v -> v.kind() == ValueKind.SIMULATED);
        assertThat(result.actions()).containsExactly(new OfferedAction(ActionKind.OPEN_GOAL, "objetivo_1"));
        assertThat(result.quality().gaps()).isEmpty();
        assertNoLeak(result, GOAL_NAME, GOAL_ID.toString());
    }

    @Test
    void flagsASimulationBeyondTheHorizon() {
        var scenario = new SimulationScenario(brl("10"), null, 0, YearMonth.of(2026, 12), null, null, null, null,
                null, null, true);
        when(simulateGoal.execute(any(), any(), any())).thenReturn(new SimulationView(brl("5500"),
                YearMonth.of(2026, 12), null, List.of(new SimulationView.ScenarioView(scenario, null, null))));

        var result = tool.collect(request(decision(Intent.GOAL_SIMULATION, null, null, "objetivo_1",
                new IntentDecision.Scenario("10", null, null, null)), pseudonyms()));

        assertThat(result.quality().gaps()).containsExactly("SIMULATION_TOO_FAR");
    }

    @Test
    void needsATargetAndAScenario() {
        var withoutTarget = tool.collect(request(decision(Intent.GOAL_SIMULATION, null, null, null, SCENARIO),
                pseudonyms()));
        var withoutScenario = tool.collect(request(decision(Intent.GOAL_SIMULATION, null, null, "objetivo_1", null),
                pseudonyms()));
        var unknownTarget = tool.collect(request(decision(Intent.GOAL_SIMULATION, null, null, "objetivo_9",
                SCENARIO), pseudonyms()));

        assertThat(withoutTarget.status()).isEqualTo(FinancialTool.Status.NEEDS_TARGET);
        assertThat(withoutScenario.status()).isEqualTo(FinancialTool.Status.NEEDS_TARGET);
        assertThat(unknownTarget.status()).isEqualTo(FinancialTool.Status.NEEDS_TARGET);
        verify(simulateGoal, never()).execute(any(), any(), any());
    }
}
