package com.smarthealthfinance.accounts.presentation.dto.response;

import com.smarthealthfinance.accounts.application.dto.AccountView;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, UUID workspaceId, String name, String type, String institutionName,
        String currency, boolean includedInTotal, String status, Instant createdAt, Instant updatedAt) {

    public static AccountResponse from(AccountView view) {
        return new AccountResponse(view.id(), view.workspaceId(), view.name(), view.type().name(),
                view.institutionName(), view.currency(), view.includedInTotal(), view.status().name(),
                view.createdAt(), view.updatedAt());
    }
}
