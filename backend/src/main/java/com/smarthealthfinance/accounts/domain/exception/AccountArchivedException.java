package com.smarthealthfinance.accounts.domain.exception;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;

public final class AccountArchivedException extends RuntimeException {
    public AccountArchivedException(AccountId id) {
        super("Account " + id + " is archived");
    }
}
