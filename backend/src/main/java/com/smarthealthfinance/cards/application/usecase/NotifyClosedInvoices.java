package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Grava {@code invoice.closed} v1 para as faturas com lançamentos que já fecharam (ADR-0026 §8). Idempotente: a marca
 * {@code closed_notified_at} é gravada na mesma transação da outbox, e as linhas são bloqueadas com
 * {@code SKIP LOCKED}. Job de sistema, sem usuário: percorre todos os Workspaces.
 */
@Service
public class NotifyClosedInvoices {

    public static final int BATCH_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(NotifyClosedInvoices.class);

    private final CardInvoiceRepository invoices;
    private final CardEvents events;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final Counter notified;

    public NotifyClosedInvoices(CardInvoiceRepository invoices, CardEvents events, BusinessCalendar calendar,
                                Clock clock, MeterRegistry meters) {
        this.invoices = invoices;
        this.events = events;
        this.calendar = calendar;
        this.clock = clock;
        this.notified = Counter.builder("shf.cards.invoice.closed")
                .description("Eventos invoice.closed gravados na outbox")
                .register(meters);
    }

    /** @return quantas faturas foram notificadas neste lote (menos que {@link #BATCH_SIZE} = não há mais) */
    @Transactional
    public int execute() {
        Instant now = clock.instant();
        List<CardInvoice> closed = invoices.lockPendingClosed(calendar.today(), BATCH_SIZE);

        for (CardInvoice invoice : closed) {
            events.invoiceClosed(invoice);
            invoices.markClosedNotified(invoice, now);
            log.info("Invoice closed invoiceId={} cardId={} workspaceId={}", invoice.id(), invoice.cardId(),
                    invoice.workspaceId());
        }
        notified.increment(closed.size());
        return closed.size();
    }
}
