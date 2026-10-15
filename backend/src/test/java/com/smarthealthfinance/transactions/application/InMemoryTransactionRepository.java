package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionPage;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Fake com semântica próxima do adapter JPA: consultas sempre filtradas por Workspace,
 * mesma ordenação da listagem, cópias sem aliasing e version incrementada no save.
 */
public final class InMemoryTransactionRepository implements TransactionRepository {

    private static final Comparator<Transaction> NEWEST_FIRST = Comparator
            .comparing(Transaction::occurredOn)
            .thenComparing(Transaction::createdAt)
            .thenComparing(transaction -> transaction.id().value())
            .reversed();

    private final Map<TransactionId, Transaction> transactions = new LinkedHashMap<>();
    private int saveCount;

    public void store(Transaction transaction) {
        transactions.put(transaction.id(), copy(transaction, transaction.version()));
    }

    public int size() {
        return transactions.size();
    }

    public int saveCount() {
        return saveCount;
    }

    @Override
    public Optional<Transaction> findById(WorkspaceId workspaceId, TransactionId transactionId) {
        return Optional.ofNullable(transactions.get(transactionId))
                .filter(transaction -> transaction.workspaceId().equals(workspaceId))
                .map(transaction -> copy(transaction, transaction.version()));
    }

    @Override
    public Optional<Transaction> findByIdForUpdate(WorkspaceId workspaceId, TransactionId transactionId) {
        return findById(workspaceId, transactionId);
    }

    @Override
    public List<Transaction> findRefundsOf(WorkspaceId workspaceId, TransactionId originalId) {
        return inWorkspace(workspaceId)
                .filter(transaction -> transaction.refundOfTransactionId().filter(originalId::equals).isPresent())
                .toList();
    }

    @Override
    public TransactionPage search(WorkspaceId workspaceId, TransactionCriteria criteria, int page, int pageSize) {
        List<Transaction> matching = inWorkspace(workspaceId)
                .filter(transaction -> matches(transaction, criteria))
                .sorted(NEWEST_FIRST)
                .toList();

        List<Transaction> items = matching.stream().skip((long) page * pageSize).limit(pageSize).toList();
        return new TransactionPage(items, matching.size());
    }

    @Override
    public List<Transaction> findAwaitingAutomation(WorkspaceId workspaceId, TransactionId after, int limit) {
        return inWorkspace(workspaceId)
                .filter(transaction -> CategoryNature.acceptedBy(transaction.type()).isPresent())
                .filter(transaction -> transaction.categoryOrigin().isEmpty() || transaction.merchant().isEmpty())
                .filter(transaction -> after == null || transaction.id().value().compareTo(after.value()) > 0)
                .sorted(Comparator.comparing(transaction -> transaction.id().value()))
                .limit(limit)
                .toList();
    }

    @Override
    public List<Transaction> findAutomatableByMerchant(WorkspaceId workspaceId, String merchantKey,
                                                       CategoryNature nature) {
        return inWorkspace(workspaceId)
                .filter(transaction -> nature.acceptingTypes().contains(transaction.type()))
                .filter(transaction -> transaction.merchant().filter(m -> m.key().equals(merchantKey)).isPresent())
                .filter(transaction -> transaction.categoryOrigin().isEmpty()
                        || transaction.categoryOrigin().get().source() != CategorySource.MANUAL)
                .toList();
    }

    @Override
    public List<WorkspaceId> workspacesWithOutdatedMerchants(String currentVersion) {
        return transactions.values().stream()
                .filter(transaction -> outdated(transaction, currentVersion))
                .map(Transaction::workspaceId)
                .distinct()
                .toList();
    }

    @Override
    public List<Transaction> findWithOutdatedMerchant(WorkspaceId workspaceId, String currentVersion,
                                                      TransactionId after, int limit) {
        return inWorkspace(workspaceId)
                .filter(transaction -> outdated(transaction, currentVersion))
                .filter(transaction -> after == null || transaction.id().value().compareTo(after.value()) > 0)
                .sorted(Comparator.comparing(transaction -> transaction.id().value()))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean merchantKeyInUse(WorkspaceId workspaceId, String merchantKey, CategoryNature nature) {
        return inWorkspace(workspaceId)
                .filter(transaction -> nature.acceptingTypes().contains(transaction.type()))
                .anyMatch(transaction -> transaction.merchant().filter(m -> m.key().equals(merchantKey)).isPresent());
    }

    private static boolean outdated(Transaction transaction, String currentVersion) {
        return transaction.merchant().filter(m -> !m.normalizerVersion().equals(currentVersion)).isPresent();
    }

    @Override
    public void add(Transaction transaction) {
        if (transactions.containsKey(transaction.id())) {
            throw new IllegalStateException("add() só insere transações novas");
        }
        store(transaction);
    }

    @Override
    public void addAll(List<Transaction> newTransactions) {
        newTransactions.forEach(this::add);
    }

    @Override
    public void save(Transaction transaction) {
        if (!transactions.containsKey(transaction.id())) {
            throw new IllegalStateException("save() só atualiza transações existentes");
        }
        saveCount++;
        transactions.put(transaction.id(), copy(transaction, transaction.version() + 1));
    }

    private Stream<Transaction> inWorkspace(WorkspaceId workspaceId) {
        return transactions.values().stream()
                .filter(transaction -> transaction.workspaceId().equals(workspaceId))
                .map(transaction -> copy(transaction, transaction.version()));
    }

    private static boolean matches(Transaction transaction, TransactionCriteria criteria) {
        return (criteria.from() == null || !transaction.occurredOn().isBefore(criteria.from()))
                && (criteria.to() == null || !transaction.occurredOn().isAfter(criteria.to()))
                && (criteria.type() == null || transaction.type() == criteria.type())
                && (criteria.status() == null || transaction.status() == criteria.status())
                && (criteria.accountId() == null || transaction.involves(criteria.accountId()))
                && (criteria.text() == null || transaction.description().value().toLowerCase(Locale.ROOT)
                .contains(criteria.text().toLowerCase(Locale.ROOT)))
                && (criteria.categoryCode() == null || transaction.category()
                .filter(category -> category.code().equals(criteria.categoryCode())).isPresent())
                && (!criteria.uncategorized() || transaction.category().isEmpty());
    }

    private static Transaction copy(Transaction t, long version) {
        return Transaction.restore(t.id(), t.workspaceId(), t.type(), t.accountId(),
                t.destinationAccountId().orElse(null), t.adjustmentDirection().orElse(null), t.amount(),
                t.occurredOn(), t.description(), t.status(), t.source(), t.refundOfTransactionId().orElse(null),
                t.category().orElse(null), t.categoryOrigin().orElse(null), t.merchant().orElse(null),
                t.reclassifiedFromTransactionId().orElse(null), t.reclassifiedAsTransactionId().orElse(null), t.createdAt(),
                t.updatedAt(), version);
    }
}
