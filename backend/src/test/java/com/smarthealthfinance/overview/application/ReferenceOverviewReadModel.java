package com.smarthealthfinance.overview.application;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.domain.model.CashFlow;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementação de referência do read model: calcula em memória, percorrendo as transações do domínio e usando
 * {@link Transaction#balanceEffectOn}. Serve de fake nos testes de Application e de oráculo no teste de paridade
 * contra o SQL do PostgreSQL (cálculo independente, mesma semântica).
 */
public final class ReferenceOverviewReadModel implements OverviewReadModel {

    private static final Comparator<Transaction> NEWEST_FIRST = Comparator
            .comparing(Transaction::occurredOn)
            .thenComparing(Transaction::createdAt)
            .thenComparing(transaction -> transaction.id().value())
            .reversed();

    private final TransactionRepository transactions;
    private final AccountRepository accounts;

    public ReferenceOverviewReadModel(TransactionRepository transactions, AccountRepository accounts) {
        this.transactions = transactions;
        this.accounts = accounts;
    }

    @Override
    public Map<AccountId, AccountFigures> accountFigures(WorkspaceId workspaceId, OverviewPeriod period,
                                                         Currency currency) {
        List<Transaction> posted = all(workspaceId).stream()
                .filter(transaction -> transaction.status() == TransactionStatus.POSTED)
                .toList();
        Map<AccountId, AccountFigures> figures = new HashMap<>();

        for (Account account : accounts.findAllByWorkspace(workspaceId)) {
            List<Transaction> involving = posted.stream().filter(t -> t.involves(account.id())).toList();
            List<Transaction> upToDate = involving.stream().filter(t -> !t.occurredOn().isAfter(period.to())).toList();
            if (upToDate.isEmpty()) {
                continue;
            }

            Money balance = upToDate.stream()
                    .map(t -> t.balanceEffectOn(account.id()))
                    .reduce(Money.zero(currency), Money::plus);
            int movements = (int) upToDate.stream().filter(t -> !t.occurredOn().isBefore(period.from())).count();
            figures.put(account.id(), new AccountFigures(balance, movements));
        }
        return figures;
    }

    @Override
    public CashFlow cashFlow(WorkspaceId workspaceId, OverviewPeriod period, Currency currency) {
        List<AccountId> scope = accounts.findAllByWorkspace(workspaceId).stream()
                .filter(account -> account.status() == AccountStatus.ACTIVE && account.includedInTotal())
                .map(Account::id)
                .toList();
        List<Transaction> inScope = all(workspaceId).stream()
                .filter(t -> t.status() == TransactionStatus.POSTED && inPeriod(t, period) && scope.contains(t.accountId()))
                .toList();

        return new CashFlow(sum(inScope, TransactionType.INCOME, currency), sum(inScope, TransactionType.EXPENSE, currency),
                sum(inScope, TransactionType.REFUND, currency));
    }

    @Override
    public Activity activity(WorkspaceId workspaceId, OverviewPeriod period, Currency currency) {
        List<Transaction> everything = all(workspaceId);
        List<Transaction> inPeriod = everything.stream().filter(t -> inPeriod(t, period)).toList();
        List<Transaction> transfers = inPeriod.stream()
                .filter(t -> t.status() == TransactionStatus.POSTED && t.type() == TransactionType.TRANSFER)
                .toList();

        return new Activity(
                everything.stream().anyMatch(t -> t.status() == TransactionStatus.POSTED),
                (int) inPeriod.stream().filter(t -> t.status() == TransactionStatus.POSTED).count(),
                (int) inPeriod.stream().filter(t -> t.status() == TransactionStatus.PENDING).count(),
                transfers.size(),
                transfers.stream().map(Transaction::amount).reduce(Money.zero(currency), Money::plus));
    }

    @Override
    public List<RecentMovement> recentMovements(WorkspaceId workspaceId, OverviewPeriod period, int limit,
                                                Currency currency) {
        return all(workspaceId).stream()
                .filter(t -> t.status() == TransactionStatus.POSTED || t.status() == TransactionStatus.PENDING)
                .filter(t -> inPeriod(t, period))
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .map(t -> new RecentMovement(t.id(), t.type(), t.adjustmentDirection().orElse(null), t.accountId(),
                        t.destinationAccountId(), t.amount(), t.occurredOn(), t.description().value(), t.status(),
                        t.refundOfTransactionId(), t.source()))
                .toList();
    }

    private List<Transaction> all(WorkspaceId workspaceId) {
        return transactions.search(workspaceId, TransactionCriteria.none(), 0,
                100_000).items();
    }

    private static boolean inPeriod(Transaction transaction, OverviewPeriod period) {
        LocalDate date = transaction.occurredOn();
        return !date.isBefore(period.from()) && !date.isAfter(period.to());
    }

    private static Money sum(List<Transaction> transactions, TransactionType type, Currency currency) {
        return transactions.stream()
                .filter(t -> t.type() == type)
                .map(Transaction::amount)
                .reduce(Money.zero(currency), Money::plus);
    }
}
