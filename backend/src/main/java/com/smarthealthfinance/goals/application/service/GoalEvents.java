package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eventos {@code goal.*} v1 (ADR-0034 §7), gravados na outbox na mesma transação do fato e só quando algo mudou. O
 * payload leva só ids, tipo, origem, status e os <b>nomes</b> dos campos alterados, nunca valores ou o nome do objetivo.
 * No M10 nenhum módulo os consome.
 */
@Service
public class GoalEvents {

    public static final String CREATED = "goal.created";
    public static final String PLAN_CHANGED = "goal.plan-changed";
    public static final String STATUS_CHANGED = "goal.status-changed";
    public static final int VERSION = 1;
    private static final String AGGREGATE = "Goal";

    private final EventOutbox outbox;

    public GoalEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void created(Goal goal) {
        append(CREATED, goal, Map.of("goalId", goal.id().value().toString(), "type", goal.type().name(),
                "origin", goal.origin().kind().name()));
    }

    /** @param fields os nomes dos campos alterados (constantes {@code Goal.FIELD_*}), em ordem estável */
    public void planChanged(Goal goal, Set<String> fields) {
        append(PLAN_CHANGED, goal, Map.of("goalId", goal.id().value().toString(), "fields", List.copyOf(fields)));
    }

    public void statusChanged(Goal goal, GoalStatus from) {
        append(STATUS_CHANGED, goal, Map.of("goalId", goal.id().value().toString(), "from", from.name(),
                "to", goal.status().name()));
    }

    private void append(String type, Goal goal, Map<String, Object> payload) {
        outbox.append(new IntegrationEvent(type, VERSION, goal.workspaceId().value(), AGGREGATE, goal.id().value(),
                null, payload));
    }
}
