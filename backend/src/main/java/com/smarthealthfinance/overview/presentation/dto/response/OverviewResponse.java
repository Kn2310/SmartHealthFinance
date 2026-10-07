package com.smarthealthfinance.overview.presentation.dto.response;

import com.smarthealthfinance.overview.application.dto.OverviewView;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrato do Overview. {@code null} em um valor monetário = sem dados (a UI mostra o empty state);
 * {@code "0.00"} = zero real. Ver {@code state}.
 */
public record OverviewResponse(UUID workspaceId, Period period, String state, Summary summary, CashFlow cashFlow,
                               List<AccountSummary> accounts, List<RecentTransaction> recentTransactions) {

    public record Period(String type, LocalDate from, LocalDate to) {}

    public record Summary(MoneyDto totalBalance, LocalDate balanceAsOf, int accountCount, int pendingTransactions) {}

    public record CashFlow(MoneyDto income, MoneyDto expense, MoneyDto refunds, MoneyDto net, Transfers transfers) {}

    public record Transfers(int count, MoneyDto volume) {}

    public record AccountSummary(UUID id, String name, String type, String institutionName, boolean includedInTotal,
                                 MoneyDto balance, int movementCount) {}

    public record AccountRef(UUID id, String name) {}

    public record RecentTransaction(UUID id, String type, String flow, String adjustmentDirection, String status,
                                    MoneyDto amount, LocalDate occurredOn, String description, AccountRef account,
                                    AccountRef destinationAccount, UUID refundOfTransactionId) {}

    public static OverviewResponse from(OverviewView view) {
        return new OverviewResponse(view.workspaceId(),
                new Period(view.period().type().name(), view.period().from(), view.period().to()),
                view.state().name(),
                new Summary(money(view.summary().totalBalance()), view.summary().balanceAsOf(),
                        view.summary().accountCount(), view.summary().pendingTransactions()),
                view.cashFlow() == null ? null : cashFlow(view.cashFlow()),
                view.accounts().stream()
                        .map(account -> new AccountSummary(account.id(), account.name(), account.type().name(),
                                account.institutionName(), account.includedInTotal(), money(account.balance()),
                                account.movementCount()))
                        .toList(),
                view.recentTransactions().stream().map(OverviewResponse::recent).toList());
    }

    private static CashFlow cashFlow(OverviewView.CashFlowView flow) {
        return new CashFlow(money(flow.income()), money(flow.expense()), money(flow.refunds()), money(flow.net()),
                new Transfers(flow.transferCount(), money(flow.transferVolume())));
    }

    private static RecentTransaction recent(OverviewView.RecentTransaction t) {
        return new RecentTransaction(t.id(), t.type().name(), t.flow().name(),
                t.adjustmentDirection() == null ? null : t.adjustmentDirection().name(), t.status().name(),
                money(t.amount()), t.occurredOn(), t.description(), ref(t.account()), ref(t.destinationAccount()),
                t.refundOfTransactionId());
    }

    private static AccountRef ref(OverviewView.AccountRef ref) {
        return ref == null ? null : new AccountRef(ref.id(), ref.name());
    }

    private static MoneyDto money(Money money) {
        return money == null ? null : new MoneyDto(money.toPlainString(), money.currency().getCurrencyCode());
    }
}
