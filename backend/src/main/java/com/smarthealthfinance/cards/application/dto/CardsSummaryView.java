package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * Resumo dos cartões ativos (ADR-0026 §3), numa chamada só.
 *
 * @param openInvoices soma do que falta pagar nas faturas que já começaram
 * @param nextDueDate  vencimento mais próximo com valor em aberto; nulo se não houver
 */
public record CardsSummaryView(Money totalLimit, Money utilized, Money openInvoices, LocalDate nextDueDate,
                               List<CardSummary> cards, LocalDate asOf, String calculationVersion) {

    public record CardSummary(CardView card, InvoiceSummaryView currentInvoice, UtilizationView utilization,
                              Money nextInvoiceCommitted) {}
}
