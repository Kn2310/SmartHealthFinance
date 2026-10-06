package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.service.AccountAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GetAccount {

    private final AccountAccess access;

    public GetAccount(AccountAccess access) {
        this.access = access;
    }

    @Transactional(readOnly = true)
    public AccountView execute(UUID workspaceId, UUID accountId) {
        return AccountView.from(access.requireAccount(workspaceId, accountId));
    }
}
