package com.smarthealthfinance.cards.application.port;

import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fatos {@code POSTED} de cartão (ADR-0026 §7 e §10): parcelas das compras, estornos e os pagamentos por dia. O SQL só
 * filtra (status, Workspace, cartão) e soma os pagamentos de cada dia; estornos nas parcelas, valores por fatura, estados e
 * utilização ficam no domínio ({@code CreditAllocator}, {@code InvoiceLedger}).
 */
public interface CardLedgerReader {

    /** Parcela de uma compra {@code POSTED}, com os dados da transação para os lançamentos da fatura. */
    record InstallmentFact(UUID transactionId, int number, int count, LocalDate purchasedOn, Money amount,
                           CardInvoiceId invoiceId, String description, String categoryCode, String merchantName,
                           String source) {}

    /** Estorno {@code POSTED} no cartão. */
    record CreditFact(UUID transactionId, LocalDate occurredOn, Money amount, UUID refundOfTransactionId,
                      String description, String categoryCode, String merchantName, String source) {}

    /** Pagamentos de fatura {@code POSTED} (TRANSFER conta → cartão) de um dia, somados. */
    record PaymentFact(LocalDate occurredOn, Money amount) {}

    /**
     * @param payments pagamentos por dia, em ordem de data; o início do acompanhamento descarta os que pagaram faturas
     *                 anteriores a ele (ADR-0025 §15)
     */
    record CardFacts(List<InstallmentFact> installments, List<CreditFact> credits, List<PaymentFact> payments) {}

    CardFacts facts(WorkspaceId workspaceId, CardId cardId);

    /** Fatos de todos os cartões do Workspace em três consultas (resumo, sem N+1). */
    Map<CardId, CardFacts> factsByCard(WorkspaceId workspaceId);
}
