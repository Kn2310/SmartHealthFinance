package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.dto.TransactionView;

import java.time.YearMonth;
import java.util.List;

/**
 * Compra no cartão e as parcelas geradas (ADR-0026 §10).
 *
 * @param replayed a mesma Idempotency-Key e o mesmo conteúdo já tinham sido processados (nada foi gravado)
 */
public record CardPurchaseView(TransactionView transaction, List<Installment> installments, String calculationVersion,
                               boolean replayed) {

    public record Installment(int number, Money amount, YearMonth dueMonth) {}
}
