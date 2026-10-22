package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Conta de pagamento padrão do cartão (ADR-0025 §1): opcional, do mesmo Workspace e ativa. Conta de outro Workspace é
 * indistinguível de inexistente ({@code ACCOUNT_NOT_FOUND}).
 */
@Service
public class PaymentAccountCheck {

    private final AccountRepository accounts;

    public PaymentAccountCheck(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** @return nulo quando não há conta padrão */
    public AccountId requireActive(WorkspaceId workspaceId, UUID accountId) {
        if (accountId == null) {
            return null;
        }
        AccountId id = new AccountId(accountId);
        accounts.findById(workspaceId, id).orElseThrow(AccountNotFoundException::new).ensureActive();
        return id;
    }
}
