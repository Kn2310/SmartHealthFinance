package com.smarthealthfinance.cards.application.port;

import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Leitura agregada dos fatos de cartão (ADR-0026 §7): o SQL só soma por fatura; estados, alocação e utilização ficam
 * no domínio ({@code InvoiceLedger}). Só transações {@code POSTED} contam. Toda consulta filtra por Workspace.
 * <p>
 * Parte B: só parcelas de compras. Créditos (estornos) e pagamentos entram na parte C, com os lançamentos.
 */
public interface CardLedgerReader {

    /** @param charges soma das parcelas, sem a parte cancelada; @param credits soma dos créditos */
    record InvoiceTotals(Money charges, Money credits) {}

    /** @param payments soma dos pagamentos {@code POSTED} do cartão */
    record CardTotals(Map<CardInvoiceId, InvoiceTotals> invoices, Money payments) {}

    /** @param installmentNumber e installmentCount nulos em créditos */
    record InvoiceItem(InvoiceItemKind kind, UUID transactionId, LocalDate occurredOn, String description,
                       String categoryCode, String merchantName, String source, Integer installmentNumber,
                       Integer installmentCount, Money amount, Money cancelledAmount) {}

    record ItemPage(List<InvoiceItem> items, long totalItems) {}

    /** @param amount valor ainda comprometido da parcela (sem a parte cancelada) */
    record CommittedInstallment(CardInvoiceId invoiceId, UUID transactionId, String description, int number,
                                int count, Money amount) {}

    CardTotals totals(WorkspaceId workspaceId, CardId cardId);

    /** Totais de todos os cartões do Workspace numa consulta só (resumo, sem N+1). */
    Map<CardId, CardTotals> totalsByCard(WorkspaceId workspaceId);

    ItemPage items(WorkspaceId workspaceId, CardId cardId, CardInvoiceId invoiceId, int offset, int limit);

    long itemCount(WorkspaceId workspaceId, CardId cardId, CardInvoiceId invoiceId);

    /** Parcelas com valor comprometido nas faturas pedidas, por fatura, data da compra e número. */
    List<CommittedInstallment> installments(WorkspaceId workspaceId, CardId cardId,
                                            Collection<CardInvoiceId> invoiceIds);
}
