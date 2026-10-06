package com.smarthealthfinance.accounts.application.dto;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;

import java.time.Instant;
import java.util.UUID;

public record AccountView(
        UUID id, UUID workspaceId, String name, AccountType type, String institutionName, String currency,
        boolean includedInTotal, AccountStatus status, Instant createdAt, Instant updatedAt
) {
    public static AccountView from(Account account) {
        return new AccountView(
                account.id().value(), account.workspaceId().value(), account.name().value(), account.type(),
                account.institutionName().map(InstitutionName::value).orElse(null),
                account.currency().getCurrencyCode(), account.includedInTotal(), account.status(),
                account.createdAt(), account.updatedAt()
        );
    }
}
