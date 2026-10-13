package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.enums.BalanceEffect;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import com.smarthealthfinance.transactions.domain.service.MerchantNormalizer;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import com.smarthealthfinance.transactions.domain.valueobject.Merchant;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;
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
 * Apenas a descrição e a categoria (atributo não financeiro, ADR-0012) são editáveis.
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
    private TransactionCategory category;
    private CategoryOrigin categoryOrigin;
    private Merchant merchant;
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
            TransactionCategory category,
            CategoryOrigin categoryOrigin,
            Merchant merchant,
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
        this.category = category;
        this.categoryOrigin = categoryOrigin;
        this.merchant = merchant;
        if (category != null && categoryOrigin == null) {
            throw new IllegalArgumentException("Categoria sem origem");
        }
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
        return newFact(id, workspaceId, type, accountId, destinationAccountId, adjustmentDirection, amount,
                occurredOn, description, initialStatus, TransactionSource.MANUAL, refundOfTransactionId, now);
    }

    /**
     * Lançamento importado de extrato (ADR-0009 §9/§10): entrada vira INCOME, saída vira EXPENSE, sempre POSTED.
     * Transferências e reembolsos nunca são inferidos.
     *
     * @param amount valor positivo da transação
     * @param inflow true para entrada (valor positivo no extrato)
     */
    public static Transaction createImported(
            TransactionId id,
            WorkspaceId workspaceId,
            AccountId accountId,
            boolean inflow,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            Instant now
    ) {
        return newFact(id, workspaceId, inflow ? TransactionType.INCOME : TransactionType.EXPENSE, accountId, null,
                null, amount, occurredOn, description, TransactionStatus.POSTED, TransactionSource.IMPORT, null, now);
    }

    private static Transaction newFact(
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
            TransactionSource source,
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

        // Comerciante só onde há categoria (ADR-0013 §1): transferência e ajuste não têm comerciante.
        Merchant merchant = CategoryNature.acceptedBy(type).isPresent()
                ? MerchantNormalizer.normalize(description.value()).orElse(null)
                : null;

        return new Transaction(id, workspaceId, type, accountId, destinationAccountId, adjustmentDirection, amount,
                occurredOn, description, initialStatus, source, refundOfTransactionId, null, null, merchant, now, now,
                0);
    }

    /** Restauração completa, com a origem da categoria e o comerciante (ADR-0013). */
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
            TransactionCategory category,
            CategoryOrigin categoryOrigin,
            Merchant merchant,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new Transaction(id, workspaceId, type, accountId, destinationAccountId, adjustmentDirection, amount,
                occurredOn, description, status, source, refundOfTransactionId, category, categoryOrigin, merchant,
                createdAt, updatedAt, version);
    }

    /** Sem comerciante; categoria, se houver, é tratada como manual (dados anteriores ao ADR-0013). */
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
            TransactionCategory category,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return restore(id, workspaceId, type, accountId, destinationAccountId, adjustmentDirection, amount, occurredOn,
                description, status, source, refundOfTransactionId, category,
                category == null ? null : CategoryOrigin.MANUAL, null, createdAt, updatedAt, version);
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

    /**
     * Atribui uma categoria do catálogo por escolha do usuário (ADR-0012 §2/§7, ADR-0013 §3). Idempotente. Vale em
     * qualquer status: a categoria não muda saldo nem fluxo de caixa. A escolha vira origem {@code MANUAL}, que
     * nenhuma regra automática sobrescreve.
     *
     * @throws InvalidValueException {@code NOT_ALLOWED} em transferência e ajuste, {@code INCOMPATIBLE_WITH_TYPE}
     *                               se a natureza não combina com o tipo e {@code INACTIVE} se a categoria saiu do
     *                               catálogo
     */
    public void categorize(Category newCategory, Instant now) {
        Objects.requireNonNull(newCategory, "category");

        CategoryNature accepted = CategoryNature.acceptedBy(type)
                .orElseThrow(() -> new InvalidValueException("categoryCode", "NOT_ALLOWED"));
        if (newCategory.nature() != accepted) {
            throw new InvalidValueException("categoryCode", "INCOMPATIBLE_WITH_TYPE");
        }

        TransactionCategory assigned = newCategory.asTransactionCategory();
        if (assigned.equals(category) && CategoryOrigin.MANUAL.equals(categoryOrigin)) {
            return;
        }
        if (!newCategory.active() && !assigned.equals(category)) {
            throw new InvalidValueException("categoryCode", "INACTIVE");
        }

        category = assigned;
        categoryOrigin = CategoryOrigin.MANUAL;
        updatedAt = now;
    }

    /**
     * Volta a "Sem categoria" (ADR-0012 §4). Idempotente. Fica registrado como escolha do usuário: a transação não
     * volta a ser categorizada por regra. Transferência e ajuste não têm categoria nem escolha a registrar.
     */
    public void clearCategory(Instant now) {
        if (CategoryNature.acceptedBy(type).isEmpty()
                || (category == null && CategoryOrigin.MANUAL.equals(categoryOrigin))) {
            return;
        }

        category = null;
        categoryOrigin = CategoryOrigin.MANUAL;
        updatedAt = now;
    }

    /**
     * Categorização por regra (ADR-0013 §2/§3). Nunca sobrescreve a escolha do usuário e só vale em tipos que aceitam
     * a natureza da categoria. Idempotente.
     *
     * @return true se a transação mudou
     */
    public boolean categorizeByRule(Category suggested, CategoryOrigin origin, Instant now) {
        Objects.requireNonNull(suggested, "category");
        Objects.requireNonNull(origin, "origin");
        if (origin.source() == CategorySource.MANUAL) {
            throw new IllegalArgumentException("Regra não produz origem MANUAL");
        }
        if (categoryOrigin != null && !categoryOrigin.source().allowsAutomation()) {
            return false;
        }
        if (!suggested.active() || CategoryNature.acceptedBy(type).filter(n -> n == suggested.nature()).isEmpty()) {
            return false;
        }

        TransactionCategory assigned = suggested.asTransactionCategory();
        if (assigned.equals(category) && origin.equals(categoryOrigin)) {
            return false;
        }

        category = assigned;
        categoryOrigin = origin;
        updatedAt = now;
        return true;
    }

    /** Ainda não tratada por regra nem pelo usuário, em um tipo que aceita categoria: elegível à regra. */
    public boolean awaitsAutomation() {
        return categoryOrigin == null && CategoryNature.acceptedBy(type).isPresent();
    }

    /** Preenche o comerciante de uma transação anterior ao ADR-0013 (nunca troca um já identificado). */
    public boolean identifyMerchant(Merchant identified, Instant now) {
        if (merchant != null || identified == null || CategoryNature.acceptedBy(type).isEmpty()) {
            return false;
        }
        merchant = identified;
        updatedAt = now;
        return true;
    }

    public boolean isVoided() {
        return status.isVoided();
    }

    /**
     * Efeito desta transação no saldo de {@code account} (positivo, negativo ou zero). Fonte única da semântica de
     * saldo (ADR-0006): só POSTED conta; transferência debita a origem e credita o destino.
     */
    public Money balanceEffectOn(AccountId account) {
        Objects.requireNonNull(account, "account");
        if (!status.affectsBalance()) {
            return Money.zero(amount.currency());
        }
        if (accountId.equals(account)) {
            return type.originEffect(adjustmentDirection) == BalanceEffect.CREDIT ? amount : amount.negate();
        }
        if (account.equals(destinationAccountId)) {
            return amount;
        }
        return Money.zero(amount.currency());
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
    public Optional<TransactionCategory> category() { return Optional.ofNullable(category); }
    /** Vazio enquanto ninguém (regra ou usuário) tratou a categoria. */
    public Optional<CategoryOrigin> categoryOrigin() { return Optional.ofNullable(categoryOrigin); }
    public Optional<Merchant> merchant() { return Optional.ofNullable(merchant); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
