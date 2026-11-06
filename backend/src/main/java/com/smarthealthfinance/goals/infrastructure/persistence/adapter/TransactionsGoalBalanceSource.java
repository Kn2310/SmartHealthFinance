package com.smarthealthfinance.goals.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.port.GoalBalanceSource;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Destinos {@code ACCOUNT} e {@code RESERVE} (ADR-0034 §8): o saldo vem do Overview (a mesma regra do saldo da Home e
 * da reserva do Health, ADR-0006 §8) e os movimentos das Transactions. Um destino do Open Finance
 * ({@code INVESTMENT_POSITION}, §9) entra como outra implementação desta porta.
 */
@Component
public class TransactionsGoalBalanceSource implements GoalBalanceSource {

    private final OverviewReadModel overview;
    private final TransactionRepository transactions;

    public TransactionsGoalBalanceSource(OverviewReadModel overview, TransactionRepository transactions) {
        this.overview = overview;
        this.transactions = transactions;
    }

    @Override
    public Map<AccountId, Money> balances(WorkspaceId workspaceId, Set<AccountId> accounts, LocalDate asOf,
                                          Currency currency) {
        Map<AccountId, OverviewReadModel.AccountFigures> figures = overview.accountFigures(workspaceId,
                OverviewPeriod.resolve(PeriodType.CUSTOM, asOf, asOf, asOf), currency);
        Map<AccountId, Money> balances = new HashMap<>();
        for (AccountId account : accounts) {
            OverviewReadModel.AccountFigures figure = figures.get(account);
            balances.put(account, figure == null ? Money.zero(currency) : figure.balance());
        }
        return balances;
    }

    @Override
    public List<Transaction> movements(WorkspaceId workspaceId, Set<AccountId> accounts, LocalDate from,
                                       LocalDate to) {
        return transactions.findPostedTouchingAccounts(workspaceId, accounts, from, to);
    }
}
