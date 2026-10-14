package com.smarthealthfinance.transactions.infrastructure.persistence.entity;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import com.smarthealthfinance.transactions.domain.valueobject.Merchant;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;
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
 * altere tipo, contas, valor ou data de um fato já gravado. Só descrição, status, categoria e o vínculo
 * {@code reclassified_as} (receita estornada por "marcar como reembolso") mudam.
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

    @Column(name = "category_code")
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_nature")
    private CategoryNature categoryNature;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_source")
    private CategorySource categorySource;

    @Column(name = "category_rule_version")
    private String categoryRuleVersion;

    @Column(name = "merchant_key")
    private String merchantKey;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "merchant_normalizer_version")
    private String merchantNormalizerVersion;

    @Column(name = "reclassified_from_transaction_id", updatable = false)
    private UUID reclassifiedFromTransactionId;

    @Column(name = "reclassified_as_transaction_id")
    private UUID reclassifiedAsTransactionId;

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
        entity.categoryCode = transaction.category().map(category -> category.code().value()).orElse(null);
        entity.categoryNature = transaction.category().map(TransactionCategory::nature).orElse(null);
        entity.categorySource = transaction.categoryOrigin().map(CategoryOrigin::source).orElse(null);
        entity.categoryRuleVersion = transaction.categoryOrigin().map(CategoryOrigin::ruleVersion).orElse(null);
        entity.merchantKey = transaction.merchant().map(Merchant::key).orElse(null);
        entity.merchantName = transaction.merchant().map(Merchant::name).orElse(null);
        entity.merchantNormalizerVersion = transaction.merchant().map(Merchant::normalizerVersion).orElse(null);
        entity.reclassifiedFromTransactionId = transaction.reclassifiedFromTransactionId().map(TransactionId::value)
                .orElse(null);
        entity.reclassifiedAsTransactionId = transaction.reclassifiedAsTransactionId().map(TransactionId::value)
                .orElse(null);
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
                categoryCode == null ? null : new TransactionCategory(new CategoryCode(categoryCode), categoryNature),
                categorySource == null ? null : new CategoryOrigin(categorySource, categoryRuleVersion),
                merchantKey == null ? null : new Merchant(merchantKey, merchantName, merchantNormalizerVersion),
                reclassifiedFromTransactionId == null ? null : new TransactionId(reclassifiedFromTransactionId),
                reclassifiedAsTransactionId == null ? null : new TransactionId(reclassifiedAsTransactionId),
                createdAt, updatedAt, version);
    }
}
