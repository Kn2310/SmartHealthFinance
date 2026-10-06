package com.smarthealthfinance.accounts.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.infrastructure.persistence.entity.AccountJpaEntity;
import com.smarthealthfinance.accounts.infrastructure.persistence.repository.AccountJpaRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaAccountRepository implements AccountRepository {

    private final AccountJpaRepository jpa;

    public JpaAccountRepository(AccountJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** Sempre filtrado pelo Workspace: conta de outro Workspace é indistinguível de inexistente. */
    @Override
    public Optional<Account> findById(WorkspaceId workspaceId, AccountId accountId) {
        return jpa.findByIdAndWorkspaceId(accountId.value(), workspaceId.value()).map(AccountJpaEntity::toDomain);
    }

    @Override
    public List<Account> findAllByWorkspace(WorkspaceId workspaceId) {
        return jpa.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId.value()).stream()
                .map(AccountJpaEntity::toDomain)
                .toList();
    }

    @Override
    public void add(Account account) {
        jpa.save(AccountJpaEntity.from(account));
    }

    /** O version vindo do domínio faz o merge falhar com OptimisticLockingFailureException se estiver obsoleto. */
    @Override
    public void save(Account account) {
        jpa.save(AccountJpaEntity.from(account));
    }
}
