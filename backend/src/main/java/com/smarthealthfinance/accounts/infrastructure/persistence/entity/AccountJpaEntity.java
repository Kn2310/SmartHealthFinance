package com.smarthealthfinance.accounts.infrastructure.persistence.entity;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountJpaEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(name = "institution_name")
    private String institutionName;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "included_in_total", nullable = false)
    private boolean includedInTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AccountJpaEntity() {
    }

    public static AccountJpaEntity from(Account account) {
        AccountJpaEntity entity = new AccountJpaEntity();

        entity.id = account.id().value();
        entity.workspaceId = account.workspaceId().value();
        entity.name = account.name().value();
        entity.type = account.type();
        entity.institutionName = account.institutionName().map(InstitutionName::value).orElse(null);
        entity.currency = account.currency().getCurrencyCode();
        entity.includedInTotal = account.includedInTotal();
        entity.status = account.status();
        entity.createdAt = account.createdAt();
        entity.updatedAt = account.updatedAt();
        entity.version = account.version();

        return entity;
    }

    public Account toDomain() {
        return Account.restore(new AccountId(id), new WorkspaceId(workspaceId), new AccountName(name), type,
                institutionName == null ? null : new InstitutionName(institutionName), Currency.getInstance(currency),
                includedInTotal, status, createdAt, updatedAt, version);
    }
}
