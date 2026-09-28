package com.smarthealthfinance.identity.application;

public interface AuthenticatedIdentityProvider {
    AuthenticatedIdentity current();
}
