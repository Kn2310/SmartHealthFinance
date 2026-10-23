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
 * Pagamento de fatura, total ou parcial (ADR-0025 §7): uma {@code TRANSFER} da conta para o cartão. Debita a conta e
 * nunca é despesa nem receita; o razão aplica o valor às faturas pela ordem de vencimento. Pagamento real (débito no
 * banco) está fora do MVP: aqui só se registra o que o usuário informou.
 */
@Service
public class RecordCardPayment {

    public static final String DEFAULT_DESCRIPTION = "Pagamento de fatura";

    private static final Logger log = LoggerFactory.getLogger(RecordCardPayment.class);

    private final CardAccess access;
    private final CreateTransaction createTransaction;

    public RecordCardPayment(CardAccess access, CreateTransaction createTransaction) {
        this.access = access;
        this.createTransaction = createTransaction;
    }

    /** @param description nula ou em branco = {@link #DEFAULT_DESCRIPTION} */
    public record Command(String idempotencyKey, UUID accountId, String amount, String currency, LocalDate occurredOn,
                          String description) {}

    @Transactional
    public CreateTransaction.Result execute(UUID workspaceId, UUID cardId, Command command) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        card.ensureActive();

        String description = command.description() == null || command.description().isBlank()
                ? DEFAULT_DESCRIPTION : command.description();
        CreateTransaction.Result result = createTransaction.executeOnCard(workspaceId, new CardCommand(
                command.idempotencyKey(), CardOperation.PAYMENT, cardId, command.accountId(), command.amount(),
                command.currency(), command.occurredOn(), description, null, null));

        if (!result.replayed()) {
            log.info("Card payment recorded cardId={} transactionId={} workspaceId={}", card.id(),
                    result.transaction().id(), card.workspaceId());
        }
        return result;
    }
}
