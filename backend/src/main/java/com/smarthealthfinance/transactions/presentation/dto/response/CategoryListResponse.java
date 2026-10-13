package com.smarthealthfinance.transactions.presentation.dto.response;

import com.smarthealthfinance.transactions.application.dto.CategoryView;

import java.util.List;

/** Catálogo na ordem de exibição, inclusive as inativas (ADR-0012 §6). */
public record CategoryListResponse(List<CategoryResponse> items) {

    /** @param nature {@code EXPENSE} (despesa e reembolso) ou {@code INCOME} (receita) */
    public record CategoryResponse(String code, String name, String nature, boolean active) {}

    public static CategoryListResponse from(List<CategoryView> views) {
        return new CategoryListResponse(views.stream()
                .map(view -> new CategoryResponse(view.code(), view.name(), view.nature().name(), view.active()))
                .toList());
    }
}
