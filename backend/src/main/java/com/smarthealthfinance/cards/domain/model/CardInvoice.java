package com.smarthealthfinance.cards.domain.model;

import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * Linha persistida de uma fatura (ADR-0025 §5, ADR-0026 §5): só as datas, o pagamento mínimo informado no arquivo e a
 * marca do {@code invoice.closed}. Nunca o valor, que é sempre derivado das parcelas, créditos e pagamentos.
 *
 * @param minimumPayment   nulo até a importação (Sessão 6.2) informar
 * @param closedNotifiedAt quando o {@code invoice.closed} foi gravado na outbox; nulo antes disso
 */
public record CardInvoice(
        CardInvoiceId id,
        WorkspaceId workspaceId,
        CardId cardId,
        InvoiceDates dates,
        Money minimumPayment,
        Instant closedNotifiedAt
) {
    public CardInvoice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(dates, "dates");
    }

    public CardInvoice withDates(InvoiceDates newDates) {
        return new CardInvoice(id, workspaceId, cardId, newDates, minimumPayment, closedNotifiedAt);
    }
}
