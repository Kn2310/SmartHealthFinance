package com.smarthealthfinance.identity.presentation.dto.response;

import com.smarthealthfinance.identity.application.dto.WorkspaceView;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String kind, String baseCurrency, String role,
        Instant createdAt) {

    public static WorkspaceResponse from(WorkspaceView view) {
        return new WorkspaceResponse(view.id(), view.name(), view.kind().name(), view.baseCurrency(),
                view.role().name(), view.createdAt());
    }
}
