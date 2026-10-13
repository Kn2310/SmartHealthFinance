package com.smarthealthfinance.transactions.presentation.dto.request;

import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import jakarta.validation.constraints.Size;

/** @param categoryCode código do catálogo; {@code null} volta a "Sem categoria" (ADR-0012 §7) */
public record CategorizeTransactionRequest(
        @Size(max = CategoryCode.MAX_LENGTH) String categoryCode) {
}
