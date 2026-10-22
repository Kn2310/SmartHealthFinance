package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardUtilization;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceBalance;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Razão de um cartão ({@code invoice-v1}): estados das faturas, alocação dos pagamentos e utilização (ADR-0025 §5,
 * §7 e §9). Puro e determinístico: o mesmo resultado para os mesmos fatos e o mesmo "hoje".
 * <p>
 * Alocação: começa com P = soma dos pagamentos e percorre as faturas já iniciadas em ordem de vencimento. Fatura com
 * valor ≤ 0 soma o crédito dela a P; com valor > 0 recebe min(valor, P). O que sobra abate as próximas faturas.
 */
public final class InvoiceLedger {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final List<InvoiceStatement> statements;
    private final Money leftover;
    private final Money totalAmount;
    private final Money payments;

    private InvoiceLedger(List<InvoiceStatement> statements, Money leftover, Money totalAmount, Money payments) {
        this.statements = List.copyOf(statements);
        this.leftover = leftover;
        this.totalAmount = totalAmount;
        this.payments = payments;
    }

    /**
     * @param invoices todas as faturas com lançamentos, mais a atual (mesmo sem valor)
     * @param payments soma dos pagamentos {@code POSTED} do cartão
     */
    public static InvoiceLedger compute(List<InvoiceBalance> invoices, Money payments, LocalDate today) {
        Objects.requireNonNull(payments, "payments");
        Objects.requireNonNull(today, "today");

        List<InvoiceBalance> ordered = new ArrayList<>(invoices);
        ordered.sort(Comparator.comparing((InvoiceBalance invoice) -> invoice.dates().dueDate())
                .thenComparing(invoice -> invoice.dates().closingMonth()));

        Money zero = Money.zero(payments.currency());
        Money available = payments;
        Money total = zero;
        List<InvoiceStatement> statements = new ArrayList<>();

        for (InvoiceBalance invoice : ordered) {
            Money amount = invoice.amount();
            total = total.plus(amount);

            if (!invoice.dates().hasStartedOn(today)) {
                statements.add(statement(invoice, InvoiceStatus.FUTURE, zero, positive(amount, zero)));
                continue;
            }

            Money paid;
            if (amount.isPositive()) {
                paid = min(amount, available);
                available = available.minus(paid);
            }
            else {
                paid = zero;
                available = available.minus(amount);
            }
            Money remaining = positive(amount.minus(paid), zero);
            statements.add(statement(invoice, status(invoice, paid, remaining, today), paid, remaining));
        }

        return new InvoiceLedger(statements, available, total, payments);
    }

    /** Em ordem de vencimento. */
    public List<InvoiceStatement> statements() {
        return statements;
    }

    /** A fatura em curso (o período contém hoje), se estiver entre as calculadas. */
    public Optional<InvoiceStatement> current() {
        return statements.stream().filter(statement -> statement.status() == InvoiceStatus.OPEN).findFirst();
    }

    /** Soma do que falta pagar nas faturas que já começaram (inclusive a aberta). */
    public Money openRemaining() {
        return statements.stream()
                .filter(statement -> statement.status() != InvoiceStatus.FUTURE)
                .map(InvoiceStatement::remaining)
                .reduce(Money.zero(payments.currency()), Money::plus);
    }

    public CardUtilization utilization(CardLimit cardLimit) {
        Money zero = Money.zero(payments.currency());
        Money limit = cardLimit.value();

        Money utilized = positive(totalAmount.minus(payments), zero);
        Money previous = zero;
        Money current = zero;
        Money future = zero;
        for (InvoiceStatement statement : statements) {
            switch (statement.status()) {
                case FUTURE -> future = future.plus(statement.remaining());
                case OPEN -> current = current.plus(statement.remaining());
                default -> previous = previous.plus(statement.remaining());
            }
        }

        // O crédito que sobrou das faturas já iniciadas abate primeiro as parcelas futuras.
        Money futureNet = positive(future.minus(leftover), zero);
        Money credit = positive(leftover.minus(future), zero);

        return new CardUtilization(limit, utilized, limit.minus(utilized), credit, percent(utilized, limit),
                percent(previous.plus(current), limit), previous, current, futureNet);
    }

    private static InvoiceStatus status(InvoiceBalance invoice, Money paid, Money remaining, LocalDate today) {
        if (!invoice.dates().isClosedOn(today)) {
            return InvoiceStatus.OPEN;
        }
        if (!remaining.isPositive()) {
            return InvoiceStatus.PAID;
        }
        if (today.isAfter(invoice.dates().effectiveDueDate())) {
            return InvoiceStatus.OVERDUE;
        }
        return paid.isPositive() ? InvoiceStatus.PARTIALLY_PAID : InvoiceStatus.CLOSED;
    }

    private static InvoiceStatement statement(InvoiceBalance invoice, InvoiceStatus status, Money paid,
                                              Money remaining) {
        return new InvoiceStatement(invoice.dates(), status, invoice.charges(), invoice.credits(), invoice.amount(),
                paid, remaining);
    }

    private static int percent(Money part, Money whole) {
        return part.amount().multiply(HUNDRED).divide(whole.amount(), 0, RoundingMode.HALF_EVEN).intValueExact();
    }

    private static Money min(Money a, Money b) {
        return a.isGreaterThan(b) ? b : a;
    }

    private static Money positive(Money value, Money zero) {
        return value.isPositive() ? value : zero;
    }
}
