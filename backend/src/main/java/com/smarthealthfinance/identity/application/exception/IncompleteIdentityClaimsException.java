package com.smarthealthfinance.identity.application.exception;

public class IncompleteIdentityClaimsException extends RuntimeException {
    public IncompleteIdentityClaimsException(String claim) {
        super("Missing or invalid claim: " + claim);
    }
}
