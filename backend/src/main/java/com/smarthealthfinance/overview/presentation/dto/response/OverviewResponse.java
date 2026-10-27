package com.smarthealthfinance.overview.presentation.dto.response;

import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.overview.application.dto.OverviewView;
import com.smarthealthfinance.overview.domain.model.CardDebts;
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
                               List<AccountSummary> accounts, List<RecentTransaction> recentTransactions,
                               InvoicesToPay cards) {

    public record Period(String type, LocalDate from, LocalDate to) {}

    public record Summary(MoneyDto totalBalance, LocalDate balanceAsOf, int accountCount, int pendingTransactions) {}

    public record CashFlow(MoneyDto income, MoneyDto expense, MoneyDto refunds, MoneyDto net, Transfers transfers) {}

    public record Transfers(int count, MoneyDto volume) {}

    public record AccountSummary(UUID id, String name, String type, String institutionName, boolean includedInTotal,
                                 MoneyDto balance, int movementCount) {}

    public record AccountRef(UUID id, String name) {}

    /** Só os 4 últimos dígitos do cartão (ADR-0025 §1). */
    public record CardRef(UUID id, String nickname, String last4) {}

    /**
     * @param account nula em compra e estorno no cartão
     * @param card    cartão da compra/estorno ou que recebe o pagamento de fatura; nulo nas demais (ADR-0026 §10)
     */
    public record RecentTransaction(UUID id, String type, String flow, String adjustmentDirection, String status,
                                    MoneyDto amount, LocalDate occurredOn, String description, AccountRef account,
                                    AccountRef destinationAccount, UUID refundOfTransactionId,
                                    String source, CardRef card) {}

    /**
     * "Faturas a pagar" e "Próximos eventos" (ADR-0006 §18): sempre em hoje, separados do saldo total, só com fatos.
     * Nulo sem cartão ativo.
     *
     * @param openInvoices o que falta pagar nas faturas já iniciadas, inclusive a atual (parcial)
     * @param nextDue      vencimento mais próximo com saldo em aberto (pode ter passado); nulo sem saldo
     */
    public record InvoicesToPay(int activeCards, MoneyDto openInvoices, InvoicesNextDue nextDue,
                                List<UpcomingInvoiceEvent> upcomingEvents, LocalDate asOf,
                                String calculationVersion) {}

    public record InvoicesNextDue(LocalDate date, MoneyDto amount) {}

    /**
     * @param kind   {@code INVOICE_CLOSING} ou {@code INVOICE_DUE}
     * @param status {@code PARTIAL} (fatura aberta: valor até agora), {@code CLOSED} (saldo em aberto) ou
     *               {@code OVERDUE}
     */
    public record UpcomingInvoiceEvent(LocalDate date, String kind, String status, String dueMonth,
                                       MoneyDto amount, CardRef card) {}

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
                view.recentTransactions().stream().map(OverviewResponse::recent).toList(),
                cards(view.cards()));
    }

    private static InvoicesToPay cards(CardDebts debts) {
        if (debts == null) {
            return null;
        }
        return new InvoicesToPay(debts.activeCards(), money(debts.openInvoices()),
                debts.nextDue() == null ? null
                        : new InvoicesNextDue(debts.nextDue().date(), money(debts.nextDue().amount())),
                debts.upcomingEvents().stream()
                        .map(event -> new UpcomingInvoiceEvent(event.date(), event.kind().name(),
                                event.status().name(), event.dueMonth().toString(), money(event.amount()),
                                new CardRef(event.card().id(), event.card().nickname(), event.card().last4())))
                        .toList(),
                debts.asOf(), InvoiceCalendar.RULES_VERSION);
    }

    private static CashFlow cashFlow(OverviewView.CashFlowView flow) {
        return new CashFlow(money(flow.income()), money(flow.expense()), money(flow.refunds()), money(flow.net()),
                new Transfers(flow.transferCount(), money(flow.transferVolume())));
    }

    private static RecentTransaction recent(OverviewView.RecentTransaction t) {
        return new RecentTransaction(t.id(), t.type().name(), t.flow().name(),
                t.adjustmentDirection() == null ? null : t.adjustmentDirection().name(), t.status().name(),
                money(t.amount()), t.occurredOn(), t.description(), ref(t.account()), ref(t.destinationAccount()),
                t.refundOfTransactionId(), t.source().name(),
                t.card() == null ? null : new CardRef(t.card().id(), t.card().nickname(), t.card().last4()));
    }

    private static AccountRef ref(OverviewView.AccountRef ref) {
        return ref == null ? null : new AccountRef(ref.id(), ref.name());
    }

    private static MoneyDto money(Money money) {
        return money == null ? null : new MoneyDto(money.toPlainString(), money.currency().getCurrencyCode());
    }
}
