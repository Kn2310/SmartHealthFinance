package com.smarthealthfinance.cards.domain.model;

import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Parcela de uma compra no cartão (ADR-0025 §6): à vista = 1 parcela. A soma das parcelas de uma compra é sempre o
 * total ({@code InstallmentSplitter}). A parte consumida por estornos não é guardada: é calculada na leitura
 * ({@code CreditAllocator}, adendo do ADR-0025 §8).
 *
 * @param transactionId a compra (EXPENSE no cartão)
 * @param purchasedOn   cópia imutável da data da compra
 * @param invoiceId     fatura em que a parcela cai
 * @param rulesVersion  versão das regras de ciclo usada na atribuição
 */
public record CardInstallment(
        WorkspaceId workspaceId,
        CardId cardId,
        UUID transactionId,
        int number,
        int count,
        LocalDate purchasedOn,
        Money amount,
        CardInvoiceId invoiceId,
        String rulesVersion
) {
    public CardInstallment {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(purchasedOn, "purchasedOn");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        if (number < 1 || number > count) {
            throw new IllegalArgumentException("Número da parcela fora de 1..count");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Parcela precisa ser positiva");
        }
    }

    public CardInstallment movedTo(CardInvoiceId newInvoice, String version) {
        return new CardInstallment(workspaceId, cardId, transactionId, number, count, purchasedOn, amount, newInvoice,
                version);
    }
}
