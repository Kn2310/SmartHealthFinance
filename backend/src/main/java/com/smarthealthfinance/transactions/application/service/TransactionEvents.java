package com.smarthealthfinance.transactions.application.service;

import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Eventos {@code transaction.*} (ADR-0014 §2), gravados na outbox na mesma transação de banco do fato. Só saem
 * quando o fato realmente mudou (as operações são idempotentes).
 * <p>
 * O payload carrega só o id e a data de negócio ({@code occurredOn}), que diz qual mês os consumidores precisam
 * recalcular. Nunca valor, descrição, categoria ou comerciante: o consumidor relê a fonte da verdade.
 * Importações não geram um evento por linha: o {@code import.completed} cobre o lote.
 */
@Service
public class TransactionEvents {

    public static final String CREATED = "transaction.created";
    public static final String POSTED = "transaction.posted";
    public static final String CANCELLED = "transaction.cancelled";
    public static final String REVERSED = "transaction.reversed";
    /** Categoria ou comerciante mudou (escolha do usuário, regra aprendida ou categorização retroativa). */
    public static final String CATEGORIZED = "transaction.categorized";

    public static final int VERSION = 1;

    private final EventOutbox outbox;

    public TransactionEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void created(Transaction transaction) {
        append(CREATED, transaction);
    }

    /** A transação acabou de mudar para {@code transaction.status()}. */
    public void statusChanged(Transaction transaction) {
        append(switch (transaction.status()) {
            case POSTED -> POSTED;
            case CANCELLED -> CANCELLED;
            case REVERSED -> REVERSED;
            case PENDING -> throw new IllegalStateException("Nenhuma transição leva a " + TransactionStatus.PENDING);
        }, transaction);
    }

    public void categorized(Transaction transaction) {
        append(CATEGORIZED, transaction);
    }

    private void append(String eventType, Transaction transaction) {
        outbox.append(new IntegrationEvent(eventType, VERSION, transaction.workspaceId().value(), "Transaction",
                transaction.id().value(), null, Map.of(
                        "transactionId", transaction.id().value().toString(),
                        "occurredOn", transaction.occurredOn().toString())));
    }
}
