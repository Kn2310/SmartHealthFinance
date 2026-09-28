package com.smarthealthfinance.identity.domain;

public final class UserDisabledException extends RuntimeException {

    public UserDisabledException(UserId id) {
        super("User " + id + " is disabled");
    }
}
