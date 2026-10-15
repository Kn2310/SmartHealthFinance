package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.application.service.AutoCategorizer;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.application.service.TransactionEvents;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * "Marcar como reembolso" (adendo de 2026-10-14 aos ADR-0005 §6/§8 e ADR-0009 §9): a receita importada que era um
 * estorno é estornada (REVERSED) e substituída por um REFUND com a mesma conta, valor, data e descrição, opcionalmente
 * vinculado à despesa original. Saldo inalterado; no fluxo de caixa a entrada sai de receitas e vai para reembolsos.
 * <p>
 * Tudo numa transação de banco, com a receita bloqueada: chamadas repetidas ou concorrentes devolvem o mesmo
 * reembolso (o banco também garante um único substituto por receita). Eventos: {@code transaction.reversed} da receita
 * e {@code transaction.created} do reembolso, que os consumidores já tratam (ADR-0014).
 */
@Service
public class MarkTransactionAsRefund {

    private static final Logger log = LoggerFactory.getLogger(MarkTransactionAsRefund.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final AutoCategorizer categorizer;
    private final TransactionEvents events;
    private final Clock clock;

    public MarkTransactionAsRefund(TransactionAccess access, TransactionRepository transactions,
                                   AutoCategorizer categorizer, TransactionEvents events, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.categorizer = categorizer;
        this.events = events;
        this.clock = clock;
    }

    /** @param refundOfTransactionId despesa original; nulo = reembolso sem vínculo (continua sendo devolução) */
    public record Command(UUID refundOfTransactionId) {}

    /** @param replayed true quando a receita já tinha sido marcada (nada foi gravado) */
    public record Result(TransactionView refund, boolean replayed) {}

    @Transactional
    public Result execute(UUID workspaceId, UUID transactionId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        TransactionId refundOf = command.refundOfTransactionId() == null
                ? null : new TransactionId(command.refundOfTransactionId());

        Transaction income = transactions.findByIdForUpdate(workspace, new TransactionId(transactionId))
                .orElseThrow(TransactionNotFoundException::new);

        Optional<TransactionId> existing = income.reclassifiedAsTransactionId();
        if (existing.isPresent()) {
            return replay(workspace, existing.get(), refundOf);
        }

        Instant now = clock.instant();
        Transaction refund = Transaction.refundReplacing(TransactionId.generate(now), income, refundOf, now);
        access.requireActiveAccount(workspace, refund.accountId());
        if (refundOf != null) {
            // Mesmas regras do reembolso manual (ADR-0005 §6), com lock no original.
            Transaction original = transactions.findByIdForUpdate(workspace, refundOf)
                    .orElseThrow(() -> new InvalidValueException("refundOfTransactionId", "NOT_FOUND"));
            original.ensureRefundable(refund.amount(), transactions.findRefundsOf(workspace, refundOf));
        }

        categorizer.forWorkspace(workspace).apply(refund, now);
        transactions.add(refund);
        income.reclassifyAsRefund(refund, now);
        transactions.save(income);

        events.statusChanged(income);
        events.created(refund);
        log.info("Transaction marked as refund transactionId={} refundId={} workspaceId={}", income.id(), refund.id(),
                workspace);

        return new Result(TransactionView.from(refund), false);
    }

    private Result replay(WorkspaceId workspace, TransactionId refundId, TransactionId requestedRefundOf) {
        Transaction refund = transactions.findById(workspace, refundId)
                .orElseThrow(() -> new IllegalStateException("Receita reclassificada aponta para reembolso inexistente"));
        if (!Objects.equals(refund.refundOfTransactionId().orElse(null), requestedRefundOf)) {
            throw new InvalidValueException("refundOfTransactionId", "ALREADY_RECLASSIFIED");
        }
        return new Result(TransactionView.from(refund), true);
    }
}
