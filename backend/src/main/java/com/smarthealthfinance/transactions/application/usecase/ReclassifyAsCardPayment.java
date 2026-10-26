package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.application.service.TransactionEvents;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CardReference;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * "Marcar como pagamento de fatura" (ADR-0025 §7, adendo de 2026-10-23): o débito importado na conta que era o
 * pagamento de uma fatura é estornado (REVERSED) e passa a apontar para uma transferência conta → cartão. Se já existe
 * um pagamento da mesma conta para o cartão, com o mesmo valor, a até {@value #MATCH_WINDOW_DAYS} dias e que ainda não
 * absorveu outro débito, é ele; senão, uma transferência nova copia o fato do extrato.
 * <p>
 * MANDATORY: Cards valida o cartão (ativo, do Workspace) e chama este caso de uso na mesma transação de banco. A
 * despesa fica bloqueada; o banco garante no máximo uma despesa por pagamento e um substituto por despesa (V13).
 * Eventos: {@code transaction.reversed} da despesa e, quando há transferência nova, {@code transaction.created}.
 */
@Service
public class ReclassifyAsCardPayment {

    public static final int MATCH_WINDOW_DAYS = 5;

    private static final Logger log = LoggerFactory.getLogger(ReclassifyAsCardPayment.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final TransactionEvents events;
    private final Clock clock;

    public ReclassifyAsCardPayment(TransactionAccess access, TransactionRepository transactions,
                                   TransactionEvents events, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param replayed        true quando a despesa já tinha sido marcada para este cartão (nada foi gravado)
     * @param matchedExisting true quando o débito foi vinculado a um pagamento já registrado
     */
    public record Result(TransactionView payment, boolean replayed, boolean matchedExisting) {}

    /**
     * @throws TransactionNotFoundException a despesa não existe no Workspace
     * @throws InvalidValueException        {@code transactionId} {@code NOT_RECLASSIFIABLE} ou
     *                                      {@code ALREADY_RECLASSIFIED}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result execute(WorkspaceId workspace, UUID transactionId, UUID cardId) {
        CardReference card = new CardReference(cardId);
        Transaction expense = transactions.findByIdForUpdate(workspace, new TransactionId(transactionId))
                .orElseThrow(TransactionNotFoundException::new);

        Optional<TransactionId> existing = expense.reclassifiedAsTransactionId();
        if (existing.isPresent()) {
            return replay(workspace, expense, existing.get(), card);
        }

        expense.ensureCardPaymentReclassifiable();
        access.requireActiveAccount(workspace, expense.accountId());
        Instant now = clock.instant();

        Optional<Transaction> match = transactions.findUnmatchedCardPayments(workspace, expense.accountId(), card,
                        expense.amount(), expense.occurredOn().minusDays(MATCH_WINDOW_DAYS),
                        expense.occurredOn().plusDays(MATCH_WINDOW_DAYS)).stream()
                .min(Comparator.<Transaction>comparingLong(payment -> Math.abs(
                                ChronoUnit.DAYS.between(expense.occurredOn(), payment.occurredOn())))
                        .thenComparing(Transaction::occurredOn)
                        .thenComparing(payment -> payment.id().value().toString()));

        Transaction payment;
        if (match.isPresent()) {
            payment = match.get();
        }
        else {
            payment = Transaction.cardPaymentReplacing(TransactionId.generate(now), expense, card, now);
            transactions.add(payment);
        }
        expense.reclassifyAsCardPayment(payment, now);
        transactions.save(expense);

        events.statusChanged(expense);
        if (match.isEmpty()) {
            events.created(payment);
        }
        log.info("Transaction marked as card payment transactionId={} paymentId={} matched={} workspaceId={}",
                expense.id(), payment.id(), match.isPresent(), workspace);

        return new Result(TransactionView.from(payment), false, match.isPresent());
    }

    private Result replay(WorkspaceId workspace, Transaction expense, TransactionId paymentId, CardReference card) {
        Transaction payment = transactions.findById(workspace, paymentId)
                .orElseThrow(() -> new IllegalStateException("Despesa reclassificada aponta para lançamento inexistente"));
        if (payment.destinationCardId().filter(card::equals).isEmpty()) {
            throw new InvalidValueException("transactionId", "ALREADY_RECLASSIFIED");
        }
        boolean matched = payment.reclassifiedFromTransactionId().filter(expense.id()::equals).isEmpty();
        return new Result(TransactionView.from(payment), true, matched);
    }
}
