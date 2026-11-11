package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;

import java.util.Objects;

/**
 * Link que o contexto oferece ao modelo (ADR-0039 §6): o modelo só pode sugerir ações desta lista. {@code target} é
 * um pseudônimo, ou nulo para telas do Workspace inteiro.
 */
public record OfferedAction(ActionKind kind, String target) {

    public OfferedAction {
        Objects.requireNonNull(kind, "kind");
        if (kind.needsTarget() == (target == null)) {
            throw new IllegalArgumentException("target is required exactly for item actions");
        }
    }
}
