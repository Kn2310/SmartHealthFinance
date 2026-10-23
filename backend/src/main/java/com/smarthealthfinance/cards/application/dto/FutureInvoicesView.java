package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Só o valor já comprometido em parcelas (ADR-0025 §10). Nenhuma estimativa. */
public record FutureInvoicesView(List<Month> months, Money totalCommitted, LocalDate asOf,
                                 String calculationVersion) {

    public record Month(YearMonth dueMonth, Money committed, List<Installment> installments) {}

    /** @param amount valor ainda comprometido da parcela (sem a parte consumida por estornos) */
    public record Installment(UUID transactionId, String description, int number, int count, Money amount) {}
}
