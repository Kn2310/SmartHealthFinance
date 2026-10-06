package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.service.AccountAccess;
import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ListAccounts {

    private final AccountAccess access;
    private final AccountRepository accounts;

    public ListAccounts(AccountAccess access, AccountRepository accounts) {
        this.access = access;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<AccountView> execute(UUID workspaceId, boolean includeArchived) {
        return accounts.findAllByWorkspace(access.requireWorkspace(workspaceId)).stream()
                .filter(account -> includeArchived || account.status() == AccountStatus.ACTIVE)
                .map(AccountView::from)
                .toList();
    }
}
