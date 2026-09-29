package com.smarthealthfinance.identity.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

public record ExternalIdentity(String issuer, String subject) {

    private static final int MAX_LENGTH = 255;

    public ExternalIdentity {
        issuer = requireText("issuer", issuer);
        subject = requireText("subject", subject);
    }

    private static String requireText(String field,String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException(field, "REQUIRED");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException(field, "TOO_LONG");
        }

        return value;
    }
}
