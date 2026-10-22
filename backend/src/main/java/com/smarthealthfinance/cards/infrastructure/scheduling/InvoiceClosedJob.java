package com.smarthealthfinance.cards.infrastructure.scheduling;

import com.smarthealthfinance.cards.application.usecase.NotifyClosedInvoices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job diário do {@code invoice.closed} (ADR-0026 §8), às 00:10 do fuso de negócio ({@code shf.cards.invoice-closed.cron}).
 * Processa em lotes até esvaziar; várias instâncias se repartem pelo {@code SKIP LOCKED}.
 */
@Component
public class InvoiceClosedJob {

    private static final Logger log = LoggerFactory.getLogger(InvoiceClosedJob.class);

    private final NotifyClosedInvoices notifyClosed;

    public InvoiceClosedJob(NotifyClosedInvoices notifyClosed) {
        this.notifyClosed = notifyClosed;
    }

    @Scheduled(cron = "${shf.cards.invoice-closed.cron:0 10 0 * * *}", zone = "${shf.overview.zone:America/Sao_Paulo}")
    public void notifyClosedInvoices() {
        try {
            while (notifyClosed.execute() == NotifyClosedInvoices.BATCH_SIZE) {
                // Próximo lote: as já notificadas saíram da consulta.
            }
        }
        catch (RuntimeException failure) {
            log.error("Invoice closed job failed error={}", failure.getClass().getSimpleName());
        }
    }
}
