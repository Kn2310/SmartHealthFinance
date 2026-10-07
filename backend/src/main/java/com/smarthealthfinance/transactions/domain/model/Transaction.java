package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fato financeiro de um Workspace (specs 05.2/05.4, ADR-0005).
 * <p>
 * Valor sempre positivo + tipo. Os campos financeiros (tipo, contas, valor, data) são imutáveis depois da
 * criação: correções são feitas por cancelamento/estorno e um novo lançamento, preservando a trilha.
 * Apenas a descrição é editável.
 */
public class Transaction {

    private final TransactionId id;
    private final WorkspaceId workspaceId;
    private final TransactionType type;
    private final AccountId accountId;
    private final AccountId destinationAccountId;
    private final AdjustmentDirection adjustmentDirection;
    private final Money amount;
    private final LocalDate occurredOn;
    private TransactionDescription description;
    private TransactionStatus status;
    private final TransactionSource source;
    private final TransactionId refundOfTransactionId;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Transaction(
            TransactionId id,
            WorkspaceId workspaceId,
            TransactionType type,
            AccountId accountId,
            AccountId destinationAccountId,
            AdjustmentDirection adjustmentDirection,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            TransactionStatus status,
            TransactionSource source,
            TransactionId refundOfTransactionId,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.type = Objects.requireNonNull(type, "type");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.destinationAccountId = destinationAccountId;
        this.adjustmentDirection = adjustmentDirection;
        this.amount = Objects.requireNonNull(amount, "amount");
        this.occurredOn = Objects.requireNonNull(occurredOn, "occurredOn");
        this.description = Objects.requireNonNull(description, "description");
        this.status = Objects.requireNonNull(status, "status");
        this.source = Objects.requireNonNull(source, "source");
        this.refundOfTransactionId = refundOfTransactionId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /**
     * Lançamento manual.
     *
     * @param destinationAccountId obrigatório para TRANSFER, proibido nos demais tipos
     * @param adjustmentDirection  obrigatória para ADJUSTMENT, proibida nos demais tipos
     * @param initialStatus        PENDING ou POSTED
     * @param refundOfTransactionId opcional e só para REFUND; a elegibilidade do original é verificada
     *                              por {@link #ensureRefundable} antes da criação
     */
    public static Transaction create(
            TransactionId id,
            WorkspaceId workspaceId,
            TransactionType type,
            AccountId accountId,
            AccountId destinationAccountId,
            AdjustmentDirection adjustmentDirection,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            TransactionStatus initialStatus,
            TransactionId refundOfTransactionId,
            Instant now
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(initialStatus, "status");

        if (!amount.isPositive()) {
            throw new InvalidValueException("amount", "NOT_POSITIVE");
        }
        if (amount.decimalPlaces() > amount.currency().getDefaultFractionDigits()) {
            throw new InvalidValueException("amount", "TOO_MANY_DECIMALS");
        }
        if (occurredOn == null) {
            throw new InvalidValueException("occurredOn", "REQUIRED");
        }
        if (initialStatus != TransactionStatus.PENDING && initialStatus != TransactionStatus.POSTED) {
            throw new InvalidValueException("status", "INVALID_INITIAL");
        }

        requireTypeSpecificFields(type, accountId, destinationAccountId, adjustmentDirection, refundOfTransactionId);

        return new Transaction(id, workspaceId, type, accountId, destinationAccountId, adjustmentDirection, amount,
                occurredOn, description, initialStatus, TransactionSource.MANUAL, refundOfTransactionId, now, now, 0);
    }

    public static Transaction restore(
            TransactionId id,
            WorkspaceId workspaceId,
            TransactionType type,
            AccountId accountId,
            AccountId destinationAccountId,
            AdjustmentDirection adjustmentDirection,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            TransactionStatus status,
            TransactionSource source,
            TransactionId refundOfTransactionId,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new Transaction(id, workspaceId, type, accountId, destinationAccountId, adjustmentDirection, amount,
                occurredOn, description, status, source, refundOfTransactionId, createdAt, updatedAt, version);
    }

    private static void requireTypeSpecificFields(
            TransactionType type,
            AccountId accountId,
            AccountId destinationAccountId,
            AdjustmentDirection adjustmentDirection,
            TransactionId refundOfTransactionId
    ) {
        if (type == TransactionType.TRANSFER) {
            if (destinationAccountId == null) {
                throw new InvalidValueException("destinationAccountId", "REQUIRED");
            }
            if (destinationAccountId.equals(accountId)) {
                throw new InvalidValueException("destinationAccountId", "SAME_ACCOUNT");
            }
        }
        else if (destinationAccountId != null) {
            throw new InvalidValueException("destinationAccountId", "NOT_ALLOWED");
        }

        if (type == TransactionType.ADJUSTMENT) {
            if (adjustmentDirection == null) {
                throw new InvalidValueException("adjustmentDirection", "REQUIRED");
            }
        }
        else if (adjustmentDirection != null) {
            throw new InvalidValueException("adjustmentDirection", "NOT_ALLOWED");
        }

        if (type != TransactionType.REFUND && refundOfTransactionId != null) {
            throw new InvalidValueException("refundOfTransactionId", "NOT_ALLOWED");
        }
    }

    /**
     * Verifica se esta transação (a original) aceita um reembolso de {@code refundAmount}.
     * Só despesas lançadas são reembolsáveis, e a soma dos reembolsos não anulados nunca passa do valor original.
     *
     * @param existingRefunds reembolsos já vinculados a esta transação
     */
    public void ensureRefundable(Money refundAmount, List<Transaction> existingRefunds) {
        if (type != TransactionType.EXPENSE || status != TransactionStatus.POSTED) {
            throw new InvalidValueException("refundOfTransactionId", "NOT_REFUNDABLE");
        }

        Money alreadyRefunded = existingRefunds.stream()
                .filter(refund -> !refund.isVoided())
                .map(Transaction::amount)
                .reduce(Money.zero(amount.currency()), Money::plus);

        if (alreadyRefunded.plus(refundAmount).isGreaterThan(amount)) {
            throw new InvalidValueException("amount", "EXCEEDS_REFUNDABLE");
        }
    }

    /** PENDING → POSTED. Idempotente. */
    public void post(Instant now) {
        transition(TransactionStatus.PENDING, TransactionStatus.POSTED, now);
    }

    /** PENDING → CANCELLED. Idempotente. Lançamento já efetivado é estornado, não cancelado. */
    public void cancel(Instant now) {
        transition(TransactionStatus.PENDING, TransactionStatus.CANCELLED, now);
    }

    /** POSTED → REVERSED. Idempotente. O fato original permanece para auditoria. */
    public void reverse(Instant now) {
        transition(TransactionStatus.POSTED, TransactionStatus.REVERSED, now);
    }

    public void updateDescription(TransactionDescription newDescription, Instant now) {
        Objects.requireNonNull(newDescription, "description");

        if (description.equals(newDescription)) {
            return;
        }

        description = newDescription;
        updatedAt = now;
    }

    public boolean isVoided() {
        return status.isVoided();
    }

    /** A conta é origem ou destino desta transação. */
    public boolean involves(AccountId account) {
        return accountId.equals(account) || account.equals(destinationAccountId);
    }

    private void transition(TransactionStatus from, TransactionStatus to, Instant now) {
        if (status == to) {
            return;
        }
        if (status != from) {
            throw new InvalidTransactionStatusTransitionException(id, status, to);
        }
        status = to;
        updatedAt = now;
    }

    public TransactionId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public TransactionType type() { return type; }
    public AccountId accountId() { return accountId; }
    public Optional<AccountId> destinationAccountId() { return Optional.ofNullable(destinationAccountId); }
    public Optional<AdjustmentDirection> adjustmentDirection() { return Optional.ofNullable(adjustmentDirection); }
    public Money amount() { return amount; }
    public LocalDate occurredOn() { return occurredOn; }
    public TransactionDescription description() { return description; }
    public TransactionStatus status() { return status; }
    public TransactionSource source() { return source; }
    public Optional<TransactionId> refundOfTransactionId() { return Optional.ofNullable(refundOfTransactionId); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
