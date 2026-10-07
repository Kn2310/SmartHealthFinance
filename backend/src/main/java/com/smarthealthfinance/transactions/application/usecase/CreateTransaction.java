package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.exception.IdempotencyKeyReusedException;
import com.smarthealthfinance.transactions.application.port.TransactionIdempotencyStore;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.application.service.TransactionFingerprint;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * Lançamento manual, idempotente por Idempotency-Key (spec 05.6, ADR-0005).
 * <p>
 * Ordem: autorização → validação pura → replay (se a chave já existe) → validação contra o estado atual
 * (contas, reembolso) → claim da chave → gravação. Tudo numa única transação de banco.
 */
@Service
public class CreateTransaction {

    private static final Logger log = LoggerFactory.getLogger(CreateTransaction.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final TransactionIdempotencyStore idempotency;
    private final Clock clock;

    public CreateTransaction(TransactionAccess access, TransactionRepository transactions,
                             TransactionIdempotencyStore idempotency, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** {@code status} nulo assume POSTED; campos específicos de tipo são nulos quando não se aplicam. */
    public record Command(String idempotencyKey, String type, UUID accountId, UUID destinationAccountId,
                          String adjustmentDirection, String amount, String currency, LocalDate occurredOn,
                          String description, String status, UUID refundOfTransactionId) {}

    /** @param replayed true quando a chave já tinha sido usada com o mesmo conteúdo (nada foi gravado) */
    public record Result(TransactionView transaction, boolean replayed) {}

    @Transactional
    public Result execute(UUID workspaceId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        IdempotencyKey key = new IdempotencyKey(command.idempotencyKey());
        Instant now = clock.instant();

        Transaction candidate = toTransaction(workspace, command, now);
        String requestHash = TransactionFingerprint.of(candidate);

        Optional<TransactionIdempotencyStore.Entry> previous = idempotency.find(workspace, key);
        if (previous.isPresent()) {
            return replay(workspace, previous.get(), requestHash);
        }

        verifyAgainstCurrentState(workspace, candidate);

        // O claim espera o commit de uma requisição concorrente com a mesma chave; se ela venceu, é replay.
        if (!idempotency.claim(workspace, key, requestHash, candidate.id(), now)) {
            return replay(workspace, idempotency.find(workspace, key).orElseThrow(), requestHash);
        }

        transactions.add(candidate);

        log.info("Transaction created transactionId={} workspaceId={}", candidate.id(), workspace);

        return new Result(TransactionView.from(candidate), false);
    }

    private static Transaction toTransaction(WorkspaceId workspace, Command command, Instant now) {
        TransactionType type = TransactionType.parse(command.type());
        if (command.accountId() == null) {
            throw new InvalidValueException("accountId", "REQUIRED");
        }
        Money amount = Money.of(command.amount(), parseCurrency(command.currency()));
        AdjustmentDirection direction = AdjustmentDirection.parseOptional(command.adjustmentDirection()).orElse(null);
        TransactionStatus status = command.status() == null || command.status().isBlank()
                ? TransactionStatus.POSTED
                : TransactionStatus.parse(command.status());

        return Transaction.create(TransactionId.generate(now), workspace, type, new AccountId(command.accountId()),
                command.destinationAccountId() == null ? null : new AccountId(command.destinationAccountId()),
                direction, amount, command.occurredOn(), new TransactionDescription(command.description()), status,
                command.refundOfTransactionId() == null ? null : new TransactionId(command.refundOfTransactionId()),
                now);
    }

    private static Currency parseCurrency(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidValueException("currency", "REQUIRED");
        }
        try {
            return Currency.getInstance(code.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidValueException("currency", "INVALID");
        }
    }

    private void verifyAgainstCurrentState(WorkspaceId workspace, Transaction candidate) {
        requireMovableAccount(workspace, candidate.accountId(), candidate);
        candidate.destinationAccountId().ifPresent(destination -> requireMovableAccount(workspace, destination,
                candidate));

        candidate.refundOfTransactionId().ifPresent(originalId -> {
            // Lock no original: reembolsos concorrentes são serializados e nunca somam mais que a despesa.
            Transaction original = transactions.findByIdForUpdate(workspace, originalId)
                    .orElseThrow(() -> new InvalidValueException("refundOfTransactionId", "NOT_FOUND"));
            original.ensureRefundable(candidate.amount(), transactions.findRefundsOf(workspace, originalId));
        });
    }

    /** MVP: moeda da transação = moeda da conta (BRL, ADR-0003/0004). */
    private void requireMovableAccount(WorkspaceId workspace, AccountId accountId, Transaction candidate) {
        Account account = access.requireActiveAccount(workspace, accountId);
        if (!account.currency().equals(candidate.amount().currency())) {
            throw new InvalidValueException("currency", "MISMATCH");
        }
    }

    private Result replay(WorkspaceId workspace, TransactionIdempotencyStore.Entry entry, String requestHash) {
        if (!entry.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        Transaction original = transactions.findById(workspace, entry.transactionId())
                .orElseThrow(() -> new IllegalStateException("Idempotency-Key aponta para transação inexistente"));
        return new Result(TransactionView.from(original), true);
    }
}
