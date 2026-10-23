package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lançamento de uma fatura (ADR-0026 §4). Em {@code CHARGE}, {@code amount} é a parcela e {@code cancelledAmount} a
 * parte consumida por estornos; em {@code CREDIT}, {@code amount} é o estorno e {@code cancelledAmount} a parte que
 * cancelou parcelas futuras. O que entra na fatura é sempre {@code amount − cancelledAmount}.
 *
 * @param installmentNumber e installmentCount nulos em créditos
 */
public record InvoiceItemView(InvoiceItemKind kind, UUID transactionId, LocalDate occurredOn, String description,
                              String categoryCode, String merchantName, String source, Integer installmentNumber,
                              Integer installmentCount, Money amount, Money cancelledAmount) {
}
