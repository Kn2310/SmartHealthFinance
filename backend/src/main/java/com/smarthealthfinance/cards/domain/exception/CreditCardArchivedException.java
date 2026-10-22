package com.smarthealthfinance.cards.domain.exception;

import com.smarthealthfinance.cards.domain.valueobject.CardId;

public final class CreditCardArchivedException extends RuntimeException {
    public CreditCardArchivedException(CardId id) {
        super("Credit card " + id + " is archived");
    }
}
