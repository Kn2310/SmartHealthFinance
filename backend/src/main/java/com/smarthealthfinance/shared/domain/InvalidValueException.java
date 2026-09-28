package com.smarthealthfinance.shared.domain;

public final class InvalidValueException extends RuntimeException {

    private final String field;
    private final String reason;

    public InvalidValueException(String filed, String reason) {
        super(filed + ": " + reason);
        this.field = filed;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
