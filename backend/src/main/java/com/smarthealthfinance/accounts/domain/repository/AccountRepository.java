package com.smarthealthfinance.accounts.domain.repository;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {

    Optional<Account> findById(WorkspaceId workspaceId, AccountId accountId);

    List<Account> findAllByWorkspace(WorkspaceId workspaceId);

    void add(Account account);

    void save(Account account);
}
