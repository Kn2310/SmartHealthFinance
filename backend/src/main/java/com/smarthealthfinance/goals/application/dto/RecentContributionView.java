package com.smarthealthfinance.goals.application.dto;

import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;

import java.util.UUID;

/** Um aporte recente de algum objetivo aberto, para "Aportes recentes" do D-Goals (ADR-0035). */
public record RecentContributionView(UUID goalId, GoalMovement contribution) {}
