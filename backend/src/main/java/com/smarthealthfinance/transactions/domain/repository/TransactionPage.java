package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.transactions.domain.model.Transaction;

import java.util.List;

public record TransactionPage(List<Transaction> items, long totalItems) {

    public TransactionPage {
        items = List.copyOf(items);
    }
}
