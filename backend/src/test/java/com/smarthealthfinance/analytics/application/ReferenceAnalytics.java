package com.smarthealthfinance.analytics.application;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.analytics.domain.model.CategoryFlow;
import com.smarthealthfinance.analytics.domain.model.DailyFlow;
import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.model.FlowTotals;
import com.smarthealthfinance.analytics.domain.model.MerchantFlow;
import com.smarthealthfinance.analytics.domain.model.MonthlyFlow;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.Merchant;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementação de referência do read model de Analytics, em memória, sobre a semântica do domínio
 * ({@code Transaction.cashFlowRole()}, categoria e comerciante da transação). O teste de paridade compara o SQL da
 * projeção com ela: mudou a semântica no domínio e não no SQL (ou o contrário), o teste quebra (ADR-0014 §5).
 */
public final class ReferenceAnalytics {

    private static final Set<CashFlowRole> CASH_FLOW = EnumSet.of(CashFlowRole.INCOME, CashFlowRole.EXPENSE,
            CashFlowRole.REFUND);

    private final List<Transaction> transactions;
    private final Map<AccountId, Account> accounts;
    private final Currency currency;

    public ReferenceAnalytics(List<Transaction> transactions, List<Account> accounts, Currency currency) {
        this.transactions = transactions;
        this.accounts = accounts.stream().collect(Collectors.toMap(Account::id, Function.identity()));
        this.currency = currency;
    }

    public List<MonthlyFlow> monthly(AnalyticsPeriod period) {
        Map<YearMonth, List<Transaction>> byMonth = new TreeMap<>(projected(period).stream()
                .collect(Collectors.groupingBy(t -> YearMonth.from(t.occurredOn()))));

        List<MonthlyFlow> months = new ArrayList<>();
        byMonth.forEach((month, items) -> {
            List<Transaction> transfers = items.stream()
                    .filter(t -> t.cashFlowRole() == CashFlowRole.TRANSFER).toList();
            months.add(new MonthlyFlow(month, new FlowTotals(sum(items, CashFlowRole.INCOME),
                    sum(items, CashFlowRole.EXPENSE), sum(items, CashFlowRole.REFUND), transfers.size(),
                    total(transfers))));
        });
        return months;
    }

    public List<CategoryFlow> byCategory(AnalyticsPeriod period) {
        record Key(CashFlowRole role, String category) {
        }
        return inCashFlow(period).stream()
                .collect(Collectors.groupingBy(t -> new Key(t.cashFlowRole(), category(t))))
                .entrySet().stream()
                .map(e -> new CategoryFlow(e.getKey().role(), e.getKey().category(), total(e.getValue()),
                        e.getValue().size()))
                .toList();
    }

    public List<MerchantFlow> byMerchant(AnalyticsPeriod period) {
        record Key(CashFlowRole role, String merchant, String category) {
        }
        return inCashFlow(period).stream()
                .collect(Collectors.groupingBy(t -> new Key(t.cashFlowRole(),
                        t.merchant().map(Merchant::key).orElse(null), category(t))))
                .entrySet().stream()
                .map(e -> new MerchantFlow(e.getKey().role(), e.getKey().merchant(),
                        e.getValue().getFirst().merchant().map(Merchant::name).orElse(null), e.getKey().category(),
                        total(e.getValue()), e.getValue().size()))
                .toList();
    }

    public List<DailyFlow> daily(AnalyticsPeriod period) {
        record Key(java.time.LocalDate day, CashFlowRole role) {
        }
        return inCashFlow(period).stream()
                .collect(Collectors.groupingBy(t -> new Key(t.occurredOn(), t.cashFlowRole())))
                .entrySet().stream()
                .map(e -> new DailyFlow(e.getKey().day(), e.getKey().role(), total(e.getValue()),
                        e.getValue().size()))
                .toList();
    }

    public List<FlowEntry> entries(AnalyticsPeriod period) {
        record Key(LocalDate day, CashFlowRole role, String category, String merchant) {
        }
        return inCashFlow(period).stream()
                .collect(Collectors.groupingBy(t -> new Key(t.occurredOn(), t.cashFlowRole(), category(t),
                        t.merchant().map(Merchant::key).orElse(null))))
                .entrySet().stream()
                .map(e -> new FlowEntry(e.getKey().day(), e.getKey().role(), e.getKey().category(),
                        e.getKey().merchant(), e.getValue().getFirst().merchant().map(Merchant::name).orElse(null),
                        total(e.getValue()), e.getValue().size()))
                .toList();
    }

    public Optional<LocalDate> firstActivity() {
        return transactions.stream()
                .filter(t -> CASH_FLOW.contains(t.cashFlowRole()))
                .filter(t -> countsInTotal(accounts.get(t.accountId())))
                .map(Transaction::occurredOn)
                .min(Comparator.naturalOrder());
    }

    /** Maiores despesas: valor desc, data desc (os dados do teste não empatam nos dois). */
    public List<ExpenseItem> largestExpenses(AnalyticsPeriod period, int limit) {
        return inCashFlow(period).stream()
                .filter(t -> t.cashFlowRole() == CashFlowRole.EXPENSE)
                .sorted(Comparator.comparing((Transaction t) -> t.amount().amount()).reversed()
                        .thenComparing(Transaction::occurredOn, Comparator.reverseOrder()))
                .limit(limit)
                .map(t -> new ExpenseItem(t.id().value(), t.occurredOn(), t.description().value(),
                        t.merchant().map(Merchant::name).orElse(null), category(t), t.amount()))
                .toList();
    }

    /** Tudo o que a projeção guarda: POSTED com papel no fluxo ou transferência, de qualquer conta. */
    private List<Transaction> projected(AnalyticsPeriod period) {
        return transactions.stream()
                .filter(t -> t.cashFlowRole() != CashFlowRole.NONE)
                .filter(t -> period.contains(t.occurredOn()))
                .toList();
    }

    /** Fluxo de caixa: receitas, despesas e reembolsos só de contas ativas incluídas no total (ADR-0006 §9). */
    private List<Transaction> inCashFlow(AnalyticsPeriod period) {
        return projected(period).stream()
                .filter(t -> CASH_FLOW.contains(t.cashFlowRole()))
                .filter(t -> countsInTotal(accounts.get(t.accountId())))
                .toList();
    }

    private Money sum(List<Transaction> items, CashFlowRole role) {
        return total(items.stream()
                .filter(t -> t.cashFlowRole() == role)
                .filter(t -> countsInTotal(accounts.get(t.accountId())))
                .toList());
    }

    private Money total(List<Transaction> items) {
        return items.stream().map(Transaction::amount).reduce(Money.zero(currency), Money::plus);
    }

    private static boolean countsInTotal(Account account) {
        Objects.requireNonNull(account, "conta da transação fora do conjunto de referência");
        return account.status() == AccountStatus.ACTIVE && account.includedInTotal();
    }

    private static String category(Transaction transaction) {
        return transaction.category().map(category -> category.code().value()).orElse(null);
    }
}
