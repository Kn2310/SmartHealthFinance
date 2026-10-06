package com.smarthealthfinance.accounts.domain.model;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

public class Account {

    private final AccountId id;
    private final WorkspaceId workspaceId;
    private AccountName name;
    private AccountType type;
    private InstitutionName institutionName;
    private final Currency currency;
    private boolean includedInTotal;
    private AccountStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Account(
            AccountId id,
            WorkspaceId workspaceId,
            AccountName name,
            AccountType type,
            InstitutionName institutionName,
            Currency currency,
            boolean includedInTotal,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.institutionName = institutionName;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.includedInTotal = includedInTotal;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** @param institutionName opcional (nulo = sem instituição) */
    public static Account create(
            AccountId id,
            WorkspaceId workspaceId,
            AccountName name,
            AccountType type,
            InstitutionName institutionName,
            Currency currency,
            boolean includedInTotal,
            Instant now
    ) {
        return new Account(id, workspaceId, name, type, institutionName, currency, includedInTotal,
                AccountStatus.ACTIVE, now, now, 0);
    }

    public static Account restore(
            AccountId id,
            WorkspaceId workspaceId,
            AccountName name,
            AccountType type,
            InstitutionName institutionName,
            Currency currency,
            boolean includedInTotal,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new Account(id, workspaceId, name, type, institutionName, currency, includedInTotal, status,
                createdAt, updatedAt, version);
    }

    /** Modal "Editar conta": substitui todos os campos editáveis. Conta arquivada não é editável. */
    public void updateDetails(
            AccountName newName,
            AccountType newType,
            InstitutionName newInstitutionName,
            boolean newIncludedInTotal,
            Instant now
    ) {
        Objects.requireNonNull(newName, "name");
        Objects.requireNonNull(newType, "type");
        ensureActive();

        if (name.equals(newName) && type == newType && Objects.equals(institutionName, newInstitutionName)
                && includedInTotal == newIncludedInTotal) {
            return;
        }

        name = newName;
        type = newType;
        institutionName = newInstitutionName;
        includedInTotal = newIncludedInTotal;
        updatedAt = now;
    }

    public void archive(Instant now) {
        if (status == AccountStatus.ARCHIVED) {
            return;
        }
        status = AccountStatus.ARCHIVED;
        updatedAt = now;
    }

    public void reactivate(Instant now) {
        if (status == AccountStatus.ACTIVE) {
            return;
        }
        status = AccountStatus.ACTIVE;
        updatedAt = now;
    }

    public void ensureActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountArchivedException(id);
        }
    }

    public AccountId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public AccountName name() { return name; }
    public AccountType type() { return type; }
    public Optional<InstitutionName> institutionName() { return Optional.ofNullable(institutionName); }
    public Currency currency() { return currency; }
    public boolean includedInTotal() { return includedInTotal; }
    public AccountStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
