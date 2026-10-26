package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.enums.BalanceEffect;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import com.smarthealthfinance.transactions.domain.service.MerchantNormalizer;
import com.smarthealthfinance.transactions.domain.valueobject.CardReference;
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
    /** Conta de origem; nula só em compra e estorno no cartão ({@link #cardId}), ADR-0025 §2. */
    private final AccountId accountId;
    private final AccountId destinationAccountId;
    /** Cartão da compra ou do estorno, alternativo a {@link #accountId}. */
    private final CardReference cardId;
    /** Cartão que recebe o pagamento de fatura (TRANSFER conta → cartão). */
    private final CardReference destinationCardId;
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
    /** Receita importada que este reembolso substitui ("marcar como reembolso"). Imutável. */
    private final TransactionId reclassifiedFromTransactionId;
    /** Reembolso que substituiu esta receita estornada ("marcar como reembolso"). */
    private TransactionId reclassifiedAsTransactionId;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Transaction(
            TransactionId id,
            WorkspaceId workspaceId,
            TransactionType type,
            AccountId accountId,
            AccountId destinationAccountId,
            CardReference cardId,
            CardReference destinationCardId,
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
            TransactionId reclassifiedFromTransactionId,
            TransactionId reclassifiedAsTransactionId,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.type = Objects.requireNonNull(type, "type");
        if ((accountId == null) == (cardId == null)) {
            throw new IllegalArgumentException("A transação tem exatamente uma origem: conta ou cartão");
        }
        if (cardId != null && type != TransactionType.EXPENSE && type != TransactionType.REFUND) {
            throw new IllegalArgumentException("No cartão só existem compra e estorno");
        }
        if (destinationCardId != null && (type != TransactionType.TRANSFER || destinationAccountId != null)) {
            throw new IllegalArgumentException("Cartão de destino só em transferência, sem conta de destino");
        }
        this.accountId = accountId;
        this.destinationAccountId = destinationAccountId;
        this.cardId = cardId;
        this.destinationCardId = destinationCardId;
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
        // Reembolso no lugar de receita (ADR-0009 §9) ou pagamento de fatura no lugar de despesa (ADR-0025 §7).
        if (reclassifiedFromTransactionId != null && type != TransactionType.REFUND
                && type != TransactionType.TRANSFER) {
            throw new IllegalArgumentException("Só um reembolso ou um pagamento de fatura substitui outro lançamento");
        }
        if (reclassifiedAsTransactionId != null && (status != TransactionStatus.REVERSED
                || (type != TransactionType.INCOME && type != TransactionType.EXPENSE))) {
            throw new IllegalArgumentException("Só uma receita ou despesa estornada é substituída");
        }
        this.reclassifiedFromTransactionId = reclassifiedFromTransactionId;
        this.reclassifiedAsTransactionId = reclassifiedAsTransactionId;
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
        Objects.requireNonNull(accountId, "accountId");
        return newFact(id, workspaceId, type, accountId, destinationAccountId, null, null, adjustmentDirection, amount,
                occurredOn, description, initialStatus, TransactionSource.MANUAL, refundOfTransactionId, null, now);
    }

    /**
     * Compra ({@code EXPENSE}) ou estorno ({@code REFUND}) no cartão (ADR-0025 §2/§8): sem conta, nasce POSTED e não
     * move o saldo de nenhuma conta. O cartão é validado pelo módulo Cards antes da criação.
     *
     * @param refundOfTransactionId só em REFUND; a elegibilidade é verificada por {@link #ensureRefundable}
     */
    public static Transaction createOnCard(
            TransactionId id,
            WorkspaceId workspaceId,
            TransactionType type,
            CardReference cardId,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            TransactionId refundOfTransactionId,
            Instant now
    ) {
        Objects.requireNonNull(cardId, "cardId");
        if (type != TransactionType.EXPENSE && type != TransactionType.REFUND) {
            throw new InvalidValueException("type", "NOT_ALLOWED_ON_CARD");
        }
        return newFact(id, workspaceId, type, null, null, cardId, null, null, amount, occurredOn, description,
                TransactionStatus.POSTED, TransactionSource.MANUAL, refundOfTransactionId, null, now);
    }

    /**
     * Pagamento de fatura (ADR-0025 §7): transferência da conta para o cartão, POSTED. Debita a conta e nunca é
     * despesa nem receita.
     */
    public static Transaction createCardPayment(
            TransactionId id,
            WorkspaceId workspaceId,
            AccountId accountId,
            CardReference destinationCardId,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            Instant now
    ) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(destinationCardId, "destinationCardId");
        return newFact(id, workspaceId, TransactionType.TRANSFER, accountId, null, null, destinationCardId, null,
                amount, occurredOn, description, TransactionStatus.POSTED, TransactionSource.MANUAL, null, null, now);
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
        Objects.requireNonNull(accountId, "accountId");
        return newFact(id, workspaceId, inflow ? TransactionType.INCOME : TransactionType.EXPENSE, accountId, null,
                null, null, null, amount, occurredOn, description, TransactionStatus.POSTED, TransactionSource.IMPORT,
                null, null, now);
    }

    /**
     * Linha importada de uma fatura de cartão (ADR-0009 §23): compra ({@code EXPENSE}) ou crédito ({@code REFUND},
     * sem vínculo), sempre POSTED. O cartão é validado pelo módulo Cards antes da criação.
     *
     * @param amount valor positivo da transação
     * @param credit true para crédito na fatura
     */
    public static Transaction createImportedOnCard(
            TransactionId id,
            WorkspaceId workspaceId,
            CardReference cardId,
            boolean credit,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            Instant now
    ) {
        Objects.requireNonNull(cardId, "cardId");
        return newFact(id, workspaceId, credit ? TransactionType.REFUND : TransactionType.EXPENSE, null, null, cardId,
                null, null, amount, occurredOn, description, TransactionStatus.POSTED, TransactionSource.IMPORT, null,
                null, now);
    }

    /**
     * Pagamento de fatura que substitui um débito importado na conta ("marcar como pagamento de fatura", ADR-0025 §7,
     * adendo). Copia conta, valor, data, descrição e origem do fato do extrato; nasce POSTED. A despesa é estornada
     * por {@link #reclassifyAsCardPayment} na mesma transação de banco.
     */
    public static Transaction cardPaymentReplacing(TransactionId id, Transaction expense, CardReference card,
                                                   Instant now) {
        Objects.requireNonNull(expense, "expense");
        Objects.requireNonNull(card, "card");
        expense.ensureCardPaymentReclassifiable();
        return newFact(id, expense.workspaceId, TransactionType.TRANSFER, expense.accountId, null, null, card, null,
                expense.amount, expense.occurredOn, expense.description, TransactionStatus.POSTED, expense.source,
                null, expense.id, now);
    }

    /**
     * Reembolso que substitui uma receita importada estornada ("marcar como reembolso", adendo de 2026-10-14 aos
     * ADR-0005 §6/§8 e ADR-0009 §9). Copia conta, valor, data, descrição e origem do fato do extrato; nasce POSTED. A
     * receita original é estornada por {@link #reclassifyAsRefund} na mesma transação de banco.
     *
     * @param refundOfTransactionId despesa original, opcional; a elegibilidade é verificada por
     *                              {@link #ensureRefundable} antes da criação
     */
    public static Transaction refundReplacing(TransactionId id, Transaction original,
                                              TransactionId refundOfTransactionId, Instant now) {
        Objects.requireNonNull(original, "original");
        original.ensureReclassifiable();
        return newFact(id, original.workspaceId, TransactionType.REFUND, original.accountId, null, null, null, null,
                original.amount, original.occurredOn, original.description, TransactionStatus.POSTED, original.source,
                refundOfTransactionId, original.id, now);
    }

    private static Transaction newFact(
            TransactionId id,
            WorkspaceId workspaceId,
            TransactionType type,
            AccountId accountId,
            AccountId destinationAccountId,
            CardReference cardId,
            CardReference destinationCardId,
            AdjustmentDirection adjustmentDirection,
            Money amount,
            LocalDate occurredOn,
            TransactionDescription description,
            TransactionStatus initialStatus,
            TransactionSource source,
            TransactionId refundOfTransactionId,
            TransactionId reclassifiedFromTransactionId,
            Instant now
    ) {
        Objects.requireNonNull(type, "type");
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

        requireTypeSpecificFields(type, accountId, destinationAccountId, destinationCardId, adjustmentDirection,
                refundOfTransactionId);

        // Comerciante só onde há categoria (ADR-0013 §1): transferência e ajuste não têm comerciante.
        Merchant merchant = CategoryNature.acceptedBy(type).isPresent()
                ? MerchantNormalizer.normalize(description.value()).orElse(null)
                : null;

        return new Transaction(id, workspaceId, type, accountId, destinationAccountId, cardId, destinationCardId,
                adjustmentDirection, amount, occurredOn, description, initialStatus, source, refundOfTransactionId,
                null, null, merchant, reclassifiedFromTransactionId, null, now, now, 0);
    }

    /** Restauração completa, com cartões (ADR-0025 §2) e o vínculo de reclassificação. */
    public static Transaction restore(
            TransactionId id,
            WorkspaceId workspaceId,
            TransactionType type,
            AccountId accountId,
            AccountId destinationAccountId,
            CardReference cardId,
            CardReference destinationCardId,
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
            TransactionId reclassifiedFromTransactionId,
            TransactionId reclassifiedAsTransactionId,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new Transaction(id, workspaceId, type, accountId, destinationAccountId, cardId, destinationCardId,
                adjustmentDirection, amount, occurredOn, description, status, source, refundOfTransactionId, category,
                categoryOrigin, merchant, reclassifiedFromTransactionId, reclassifiedAsTransactionId, createdAt,
                updatedAt, version);
    }

    /** Restauração de transação de conta, com o vínculo de reclassificação ("marcar como reembolso"). */
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
            TransactionId reclassifiedFromTransactionId,
            TransactionId reclassifiedAsTransactionId,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return restore(id, workspaceId, type, accountId, destinationAccountId, null, null, adjustmentDirection,
                amount, occurredOn, description, status, source, refundOfTransactionId, category, categoryOrigin,
                merchant, reclassifiedFromTransactionId, reclassifiedAsTransactionId, createdAt, updatedAt, version);
    }

    /** Com a origem da categoria e o comerciante (ADR-0013), sem reclassificação. */
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
        return restore(id, workspaceId, type, accountId, destinationAccountId, adjustmentDirection, amount,
                occurredOn, description, status, source, refundOfTransactionId, category, categoryOrigin, merchant,
                null, null, createdAt, updatedAt, version);
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
            CardReference destinationCardId,
            AdjustmentDirection adjustmentDirection,
            TransactionId refundOfTransactionId
    ) {
        if (type == TransactionType.TRANSFER) {
            if (destinationAccountId == null && destinationCardId == null) {
                throw new InvalidValueException("destinationAccountId", "REQUIRED");
            }
            if (destinationAccountId != null && destinationAccountId.equals(accountId)) {
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

    /**
     * Estorna esta receita importada porque ela era um reembolso, apontando para o {@code refund} que a substitui
     * (criado por {@link #refundReplacing}). POSTED → REVERSED: o saldo não muda (os dois creditam a mesma conta), e
     * o fato do extrato permanece para auditoria e para a deduplicação de reimportações.
     */
    public void reclassifyAsRefund(Transaction refund, Instant now) {
        Objects.requireNonNull(refund, "refund");
        ensureReclassifiable();
        if (!id.equals(refund.reclassifiedFromTransactionId)) {
            throw new IllegalArgumentException("O reembolso não substitui esta receita");
        }
        transition(TransactionStatus.POSTED, TransactionStatus.REVERSED, now);
        reclassifiedAsTransactionId = refund.id;
    }

    /**
     * Estorna esta despesa importada porque ela era o pagamento da fatura de um cartão, apontando para a transferência
     * que a substitui: a criada por {@link #cardPaymentReplacing} ou um pagamento manual já registrado (ADR-0025 §7,
     * adendo). POSTED → REVERSED; o fato do extrato permanece para auditoria e para a deduplicação de reimportações.
     */
    public void reclassifyAsCardPayment(Transaction payment, Instant now) {
        Objects.requireNonNull(payment, "payment");
        ensureCardPaymentReclassifiable();
        if (payment.type != TransactionType.TRANSFER || payment.destinationCardId == null
                || payment.status != TransactionStatus.POSTED || !Objects.equals(accountId, payment.accountId)
                || !amount.equals(payment.amount)) {
            throw new IllegalArgumentException("O pagamento não corresponde a esta despesa");
        }
        transition(TransactionStatus.POSTED, TransactionStatus.REVERSED, now);
        reclassifiedAsTransactionId = payment.id;
    }

    /** Só despesa importada e lançada numa conta pode virar pagamento de fatura (ADR-0025 §7, adendo). */
    public void ensureCardPaymentReclassifiable() {
        if (type != TransactionType.EXPENSE || source != TransactionSource.IMPORT || accountId == null) {
            throw new InvalidValueException("transactionId", "NOT_RECLASSIFIABLE");
        }
        if (status != TransactionStatus.POSTED) {
            throw new InvalidTransactionStatusTransitionException(id, status, TransactionStatus.REVERSED);
        }
    }

    /**
     * Só receita importada e lançada pode virar reembolso: o problema é o estorno do extrato entrar como receita
     * (ADR-0009 §9). Lançamentos manuais já escolhem o tipo REFUND na criação.
     */
    private void ensureReclassifiable() {
        if (type != TransactionType.INCOME || source != TransactionSource.IMPORT) {
            throw new InvalidValueException("transactionId", "NOT_RECLASSIFIABLE");
        }
        if (status != TransactionStatus.POSTED) {
            throw new InvalidTransactionStatusTransitionException(id, status, TransactionStatus.REVERSED);
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

    /**
     * Recalcula o comerciante com a versão atual do {@link MerchantNormalizer} (adendo de 2026-10-14 ao ADR-0013 §1).
     * Só para transações que já têm comerciante de uma versão anterior; a categoria não muda (nem a escolhida pelo
     * usuário, nem a de regra). A chave vem da descrição atual. Idempotente.
     *
     * @return true se a transação mudou (chave, nome ou versão)
     */
    public boolean renormalizeMerchant(Instant now) {
        if (merchant == null || MerchantNormalizer.VERSION.equals(merchant.normalizerVersion())) {
            return false;
        }
        merchant = MerchantNormalizer.normalize(description.value()).orElse(null);
        updatedAt = now;
        return true;
    }

    public boolean isVoided() {
        return status.isVoided();
    }

    /**
     * Efeito desta transação no saldo de {@code account} (positivo, negativo ou zero). Fonte única da semântica de
     * saldo (ADR-0006): só POSTED conta; transferência debita a origem e credita o destino. Compra e estorno no cartão
     * não movem conta nenhuma; o pagamento de fatura só debita a conta de origem (ADR-0025 §3).
     */
    public Money balanceEffectOn(AccountId account) {
        Objects.requireNonNull(account, "account");
        if (!status.affectsBalance()) {
            return Money.zero(amount.currency());
        }
        if (account.equals(accountId)) {
            return type.originEffect(adjustmentDirection) == BalanceEffect.CREDIT ? amount : amount.negate();
        }
        if (account.equals(destinationAccountId)) {
            return amount;
        }
        return Money.zero(amount.currency());
    }

    /**
     * Papel desta transação no fluxo de caixa (ADR-0006 §9): só POSTED conta; as demais são {@code NONE}.
     */
    public CashFlowRole cashFlowRole() {
        return status.affectsBalance() ? CashFlowRole.of(type) : CashFlowRole.NONE;
    }

    /** A conta é origem ou destino desta transação. */
    public boolean involves(AccountId account) {
        return account.equals(accountId) || account.equals(destinationAccountId);
    }

    /** O cartão é origem (compra, estorno) ou destino (pagamento) desta transação. */
    public boolean involves(CardReference card) {
        return card.equals(cardId) || card.equals(destinationCardId);
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
    /** Nula só em compra e estorno no cartão: use {@link #cardId()} nesse caso. */
    public AccountId accountId() { return accountId; }
    public Optional<AccountId> destinationAccountId() { return Optional.ofNullable(destinationAccountId); }
    public Optional<CardReference> cardId() { return Optional.ofNullable(cardId); }
    public Optional<CardReference> destinationCardId() { return Optional.ofNullable(destinationCardId); }
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
    public Optional<TransactionId> reclassifiedFromTransactionId() {
        return Optional.ofNullable(reclassifiedFromTransactionId);
    }
    public Optional<TransactionId> reclassifiedAsTransactionId() {
        return Optional.ofNullable(reclassifiedAsTransactionId);
    }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
