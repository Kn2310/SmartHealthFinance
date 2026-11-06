package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.dto.SimulationView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.application.service.GoalSimulator;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * {@code RunSimulation} sobre um objetivo (05.3, ADR-0034 §5, ADR-0036): de 1 a 5 cenários sobre o que falta hoje.
 * Só leitura: nada é gravado e o objetivo não muda ("Aplicar" é o {@code PUT .../plan}).
 */
@Service
public class SimulateGoal {

    private final GoalAccess access;
    private final GoalReader reader;
    private final GoalSimulator simulator;

    public SimulateGoal(GoalAccess access, GoalReader reader, GoalSimulator simulator) {
        this.access = access;
        this.reader = reader;
        this.simulator = simulator;
    }

    @Transactional(readOnly = true)
    public SimulationView execute(UUID workspaceId, UUID goalId, List<GoalSimulator.Scenario> scenarios) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        GoalView goal = reader.view(workspace, access.requireGoal(workspace, goalId));
        return simulator.simulate(workspace, goal.progress().remaining(), goal.plannedMonthly(), goal.deadline(),
                scenarios);
    }
}
