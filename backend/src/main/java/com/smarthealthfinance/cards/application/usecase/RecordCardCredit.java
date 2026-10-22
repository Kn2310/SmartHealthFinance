package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction.CardCommand;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction.CardOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Estorno ou crédito no cartão (ADR-0025 §8): um {@code REFUND} no cartão, opcionalmente vinculado a uma compra do
 * mesmo cartão (regras do ADR-0005 §6). Na leitura, o estorno consome as parcelas ainda não faturadas da compra e o
 * restante vira crédito na fatura da data dele.
 */
@Service
public class RecordCardCredit {

    private static final Logger log = LoggerFactory.getLogger(RecordCardCredit.class);

    private final CardAccess access;
    private final CreateTransaction createTransaction;

    public RecordCardCredit(CardAccess access, CreateTransaction createTransaction) {
        this.access = access;
        this.createTransaction = createTransaction;
    }

    public record Command(String idempotencyKey, String amount, String currency, LocalDate occurredOn,
                          String description, UUID refundOfTransactionId) {}

    @Transactional
    public CreateTransaction.Result execute(UUID workspaceId, UUID cardId, Command command) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        card.ensureActive();

        CreateTransaction.Result result = createTransaction.executeOnCard(workspaceId, new CardCommand(
                command.idempotencyKey(), CardOperation.CREDIT, cardId, null, command.amount(), command.currency(),
                command.occurredOn(), command.description(), command.refundOfTransactionId(), null));

        if (!result.replayed()) {
            log.info("Card credit recorded cardId={} transactionId={} workspaceId={}", card.id(),
                    result.transaction().id(), card.workspaceId());
        }
        return result;
    }
}
