package com.smarthealthfinance.overview.application.dto;

import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.overview.domain.enums.MovementFlow;
import com.smarthealthfinance.overview.domain.enums.OverviewState;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Visão consolidada da Home (ADR-0006). Valores monetários ausentes ({@code null}) significam "sem dados" — nunca
 * zero: ver {@link OverviewState}.
 *
 * @param cashFlow nulo em NO_ACCOUNTS e NO_TRANSACTIONS
 */
public record OverviewView(UUID workspaceId, Period period, OverviewState state, Summary summary, CashFlowView cashFlow,
                           List<AccountSummary> accounts, List<RecentTransaction> recentTransactions) {

    public record Period(PeriodType type, LocalDate from, LocalDate to) {}

    /**
     * @param totalBalance      nulo sem dados; soma das contas ativas incluídas no total
     * @param balanceAsOf       data em que o saldo é medido (fim do período)
     * @param pendingTransactions PENDING no período: ainda fora do saldo e do fluxo de caixa
     */
    public record Summary(Money totalBalance, LocalDate balanceAsOf, int accountCount, int pendingTransactions) {}

    /**
     * @param income    INCOME lançadas
     * @param expense   EXPENSE lançadas (bruto)
     * @param refunds   REFUND lançadas; devolvem despesa, não são receita
     * @param net       income + refunds − expense
     * @param transferCount informativo: transferências não entram em income nem em expense
     */
    public record CashFlowView(Money income, Money expense, Money refunds, Money net, int transferCount,
                               Money transferVolume) {}

    /** @param balance nulo sem dados; contas excluídas do total aparecem, mas não somam em {@link Summary} */
    public record AccountSummary(UUID id, String name, AccountType type, String institutionName,
                                 boolean includedInTotal, Money balance, int movementCount) {}

    public record AccountRef(UUID id, String name) {}

    public record RecentTransaction(UUID id, TransactionType type, MovementFlow flow,
                                    AdjustmentDirection adjustmentDirection, TransactionStatus status, Money amount,
                                    LocalDate occurredOn, String description, AccountRef account,
                                    AccountRef destinationAccount, UUID refundOfTransactionId,
                                    TransactionSource source) {}
}
