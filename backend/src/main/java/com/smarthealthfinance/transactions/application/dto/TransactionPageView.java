package com.smarthealthfinance.transactions.application.dto;

import java.util.List;

public record TransactionPageView(List<TransactionView> items, int page, int pageSize, long totalItems) {

    public TransactionPageView {
        items = List.copyOf(items);
    }
}
