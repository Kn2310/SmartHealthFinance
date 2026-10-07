package com.smarthealthfinance.transactions.presentation.dto.response;

import com.smarthealthfinance.transactions.application.dto.TransactionPageView;

import java.util.List;

/** Paginação por offset (spec 05.6). */
public record TransactionPageResponse(List<TransactionResponse> items, int page, int pageSize, long totalItems) {

    public static TransactionPageResponse from(TransactionPageView view) {
        return new TransactionPageResponse(view.items().stream().map(TransactionResponse::from).toList(), view.page(),
                view.pageSize(), view.totalItems());
    }
}
