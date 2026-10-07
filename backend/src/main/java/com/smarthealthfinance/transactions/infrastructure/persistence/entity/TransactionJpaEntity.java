package com.smarthealthfinance.transactions.infrastructure.persistence.entity;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

/**
 * Campos financeiros com {@code updatable = false}: além do domínio, o mapeamento também impede que um save
 * altere tipo, contas, valor ou data de um fato já gravado. Só descrição e status mudam.
 */
@Entity
@Table(name = "transactions")
public class TransactionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "destination_account_id", updatable = false)
    private UUID destinationAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_direction", updatable = false)
    private AdjustmentDirection adjustmentDirection;

    @Column(nullable = false, updatable = false, precision = 19, scale = Money.SCALE)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionSource source;

    @Column(name = "refund_of_transaction_id", updatable = false)
    private UUID refundOfTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TransactionJpaEntity() {
    }

    public static TransactionJpaEntity from(Transaction transaction) {
        TransactionJpaEntity entity = new TransactionJpaEntity();

        entity.id = transaction.id().value();
        entity.workspaceId = transaction.workspaceId().value();
        entity.type = transaction.type();
        entity.accountId = transaction.accountId().value();
        entity.destinationAccountId = transaction.destinationAccountId().map(AccountId::value).orElse(null);
        entity.adjustmentDirection = transaction.adjustmentDirection().orElse(null);
        entity.amount = transaction.amount().amount();
        entity.currency = transaction.amount().currency().getCurrencyCode();
        entity.occurredOn = transaction.occurredOn();
        entity.description = transaction.description().value();
        entity.status = transaction.status();
        entity.source = transaction.source();
        entity.refundOfTransactionId = transaction.refundOfTransactionId().map(TransactionId::value).orElse(null);
        entity.createdAt = transaction.createdAt();
        entity.updatedAt = transaction.updatedAt();
        entity.version = transaction.version();

        return entity;
    }

    public Transaction toDomain() {
        return Transaction.restore(new TransactionId(id), new WorkspaceId(workspaceId), type, new AccountId(accountId),
                destinationAccountId == null ? null : new AccountId(destinationAccountId), adjustmentDirection,
                new Money(amount, Currency.getInstance(currency)), occurredOn, new TransactionDescription(description),
                status, source, refundOfTransactionId == null ? null : new TransactionId(refundOfTransactionId),
                createdAt, updatedAt, version);
    }
}
