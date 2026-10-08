package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.List;
import java.util.Optional;

/** Toda operação é escopada pelo Workspace: transação de outro Workspace é indistinguível de inexistente. */
public interface TransactionRepository {

    Optional<Transaction> findById(WorkspaceId workspaceId, TransactionId transactionId);

    /** Bloqueia a linha até o fim da transação (serializa reembolsos concorrentes do mesmo original). */
    Optional<Transaction> findByIdForUpdate(WorkspaceId workspaceId, TransactionId transactionId);

    List<Transaction> findRefundsOf(WorkspaceId workspaceId, TransactionId originalId);

    /** Mais recentes primeiro: occurredOn desc, createdAt desc, id desc. */
    TransactionPage search(WorkspaceId workspaceId, TransactionCriteria criteria, int page, int pageSize);

    void add(Transaction transaction);

    /** Inserção em lote de transações novas (importação, ADR-0009): sem leitura prévia por linha. */
    void addAll(List<Transaction> transactions);

    void save(Transaction transaction);
}
