package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.port.TransactionSummaryReader;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;

import java.util.Currency;
import java.util.List;
import java.util.function.Predicate;

/**
 * Referência do resumo (ADR-0016) sobre a semântica do domínio: a busca da listagem (mesmos filtros, sem status) e
 * {@code Transaction.cashFlowRole()}. É o fake dos testes de aplicação e o oráculo do teste de paridade do SQL.
 */
public final class ReferenceTransactionSummary implements TransactionSummaryReader {

    private final TransactionRepository transactions;
    private final AccountRepository accounts;

    public ReferenceTransactionSummary(TransactionRepository transactions, AccountRepository accounts) {
        this.transactions = transactions;
        this.accounts = accounts;
    }

    @Override
    public TransactionSummary summarize(WorkspaceId workspaceId, TransactionCriteria criteria, Currency currency) {
        TransactionCriteria anyStatus = new TransactionCriteria(criteria.from(), criteria.to(), criteria.type(), null,
                criteria.accountId(), criteria.text(), criteria.categoryCode(), criteria.uncategorized());
        List<Transaction> matching = transactions.search(workspaceId, anyStatus, 0, Integer.MAX_VALUE).items();

        Predicate<Transaction> inFlow = criteria.accountId() != null
                ? t -> t.accountId().equals(criteria.accountId())
                : t -> accounts.findById(workspaceId, t.accountId())
                        .filter(account -> account.status() == AccountStatus.ACTIVE)
                        .filter(Account::includedInTotal)
                        .isPresent();

        List<Transaction> transfers = matching.stream().filter(t -> t.cashFlowRole() == CashFlowRole.TRANSFER).toList();
        return new TransactionSummary(
                sum(matching, CashFlowRole.INCOME, inFlow, currency),
                sum(matching, CashFlowRole.EXPENSE, inFlow, currency),
                sum(matching, CashFlowRole.REFUND, inFlow, currency),
                transfers.size(),
                transfers.stream().map(Transaction::amount).reduce(Money.zero(currency), Money::plus),
                (int) matching.stream().filter(t -> t.status() == TransactionStatus.POSTED).count(),
                (int) matching.stream().filter(t -> t.status() == TransactionStatus.PENDING).count());
    }

    private static Money sum(List<Transaction> items, CashFlowRole role, Predicate<Transaction> inFlow,
                             Currency currency) {
        return items.stream()
                .filter(t -> t.cashFlowRole() == role)
                .filter(inFlow)
                .map(Transaction::amount)
                .reduce(Money.zero(currency), Money::plus);
    }
}
