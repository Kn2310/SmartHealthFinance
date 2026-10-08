package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
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
                .contains(criteria.text().toLowerCase(Locale.ROOT)));
    }

    private static Transaction copy(Transaction t, long version) {
        return Transaction.restore(t.id(), t.workspaceId(), t.type(), t.accountId(),
                t.destinationAccountId().orElse(null), t.adjustmentDirection().orElse(null), t.amount(),
                t.occurredOn(), t.description(), t.status(), t.source(), t.refundOfTransactionId().orElse(null),
                t.createdAt(), t.updatedAt(), version);
    }
}
