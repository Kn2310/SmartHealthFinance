package com.smarthealthfinance.cards.domain.repository;

import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface CardInvoiceRepository {

    /** Faturas persistidas do cartão, em ordem de mês de fechamento. */
    List<CardInvoice> findByCard(WorkspaceId workspaceId, CardId cardId);

    /** Faturas persistidas de todos os cartões do Workspace, por cartão e mês de fechamento. */
    List<CardInvoice> findByWorkspace(WorkspaceId workspaceId);

    void add(CardInvoice invoice);

    /** Atualiza só as datas (recálculo do ciclo das faturas não fechadas). */
    void updateDates(List<CardInvoice> invoices);

    /** Vencimento, origem das datas e pagamento mínimo informados no arquivo (ADR-0009 §21). */
    void updateStatement(CardInvoice invoice);

    /** Remove faturas não fechadas que ficaram sem parcelas depois de uma reatribuição. */
    void deleteAll(List<CardInvoice> invoices);

    /**
     * Job de sistema do {@code invoice.closed} (ADR-0026 §8), em todos os Workspaces: faturas já fechadas e ainda não
     * notificadas, bloqueadas com {@code SKIP LOCKED} para várias instâncias se repartirem. Faturas anteriores ao
     * início do acompanhamento do cartão não são notificadas enquanto ele não recuar (ADR-0025 §15).
     */
    List<CardInvoice> lockPendingClosed(LocalDate today, int limit);

    void markClosedNotified(CardInvoice invoice, Instant notifiedAt);
}
