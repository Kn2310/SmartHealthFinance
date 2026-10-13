package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma despesa individual ("Maiores gastos", ADR-0015 §6): POSTED, de conta ativa incluída no total.
 *
 * @param merchantName nulo se o comerciante não foi identificado
 * @param categoryCode nulo = "Sem categoria"
 */
public record ExpenseItem(UUID transactionId, LocalDate occurredOn, String description, String merchantName,
                          String categoryCode, Money amount) {

    public ExpenseItem {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(amount, "amount");
    }
}
