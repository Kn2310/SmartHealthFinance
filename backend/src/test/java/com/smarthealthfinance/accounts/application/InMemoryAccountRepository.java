package com.smarthealthfinance.accounts.application;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fake com semântica próxima do adapter JPA: consultas sempre filtradas por Workspace,
 * cópias sem aliasing e version incrementada no save.
 */
public final class InMemoryAccountRepository implements AccountRepository {

    private final Map<AccountId, Account> accounts = new LinkedHashMap<>();
    private int saveCount;

    public void store(Account account) {
        accounts.put(account.id(), copy(account, account.version()));
    }

    public int size() {
        return accounts.size();
    }

    public int saveCount() {
        return saveCount;
    }

    @Override
    public Optional<Account> findById(WorkspaceId workspaceId, AccountId accountId) {
        return Optional.ofNullable(accounts.get(accountId))
                .filter(account -> account.workspaceId().equals(workspaceId))
                .map(account -> copy(account, account.version()));
    }

    @Override
    public List<Account> findAllByWorkspace(WorkspaceId workspaceId) {
        return accounts.values().stream()
                .filter(account -> account.workspaceId().equals(workspaceId))
                .map(account -> copy(account, account.version()))
                .toList();
    }

    @Override
    public void add(Account account) {
        if (accounts.containsKey(account.id())) {
            throw new IllegalStateException("add() só insere contas novas");
        }
        store(account);
    }

    @Override
    public void save(Account account) {
        if (!accounts.containsKey(account.id())) {
            throw new IllegalStateException("save() só atualiza contas existentes");
        }
        saveCount++;
        accounts.put(account.id(), copy(account, account.version() + 1));
    }

    private static Account copy(Account account, long version) {
        return Account.restore(account.id(), account.workspaceId(), account.name(), account.type(),
                account.institutionName().orElse(null), account.currency(), account.includedInTotal(),
                account.status(), account.createdAt(), account.updatedAt(), version);
    }
}
