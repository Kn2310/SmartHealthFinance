package com.smarthealthfinance.identity.domain.exception;

import com.smarthealthfinance.identity.domain.valueobject.UserId;

public final class UserDisabledException extends RuntimeException {

    public UserDisabledException(UserId id) {
        super("User " + id + " is disabled");
    }
}
