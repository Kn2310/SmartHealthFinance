package com.smarthealthfinance.identity.application;

public final class UserNotProvisionedException extends RuntimeException {
    public UserNotProvisionedException() {
        super("Authenticated identity has no provisioned user");
    }
}
