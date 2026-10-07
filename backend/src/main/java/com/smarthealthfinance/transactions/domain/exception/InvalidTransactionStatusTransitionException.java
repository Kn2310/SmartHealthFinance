package com.smarthealthfinance.transactions.domain.exception;

import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

public final class InvalidTransactionStatusTransitionException extends RuntimeException {
    public InvalidTransactionStatusTransitionException(TransactionId id, TransactionStatus from, TransactionStatus to) {
        super("Transaction " + id + " cannot go from " + from + " to " + to);
    }
}
