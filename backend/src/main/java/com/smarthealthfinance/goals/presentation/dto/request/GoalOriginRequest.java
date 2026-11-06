package com.smarthealthfinance.goals.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** Origem do objetivo (ADR-0034 §1): {@code insightId} só, e obrigatoriamente, com {@code INSIGHT}. */
public record GoalOriginRequest(
        @NotBlank @Schema(allowableValues = {"MANUAL", "INSIGHT", "HEALTH"}) String kind,
        UUID insightId) {
}
