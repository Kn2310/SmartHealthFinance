package com.smarthealthfinance.identity.application;

public class IncompleteIdentityClaimsException extends RuntimeException {
    public IncompleteIdentityClaimsException(String claim) {
        super("Missing or invalid claim: " + claim);
    }
}
