package com.smarthealthfinance.overview.domain.model;

import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.overview.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.overview.domain.enums.UpcomingEventStatus;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * "Faturas a pagar" e "Próximos eventos" da Home (ADR-0006 §18), sempre em hoje. A dívida dos cartões fica aqui,
 * separada do saldo total, e sem "saldo líquido". Os números são os do razão de Cards (ADR-0025): nada é estimado.
 *
 * @param openInvoices  soma do que falta pagar nas faturas já iniciadas, inclusive a atual (parcial)
 * @param nextDue       o vencimento mais próximo com saldo em aberto (pode já ter passado); nulo sem saldo
 * @param upcomingEvents fechamentos e vencimentos até hoje + {@value #HORIZON_DAYS} dias, mais as faturas vencidas
 */
public record CardDebts(int activeCards, Money openInvoices, NextDue nextDue, List<UpcomingEvent> upcomingEvents,
                        LocalDate asOf) {

    public static final int HORIZON_DAYS = 30;

    public record NextDue(LocalDate date, Money amount) {}

    /** Só os 4 últimos dígitos do cartão (ADR-0025 §1). */
    public record CardLabel(UUID id, String nickname, String last4) {}

    public record UpcomingEvent(LocalDate date, UpcomingEventKind kind, UpcomingEventStatus status,
                                YearMonth dueMonth, Money amount, CardLabel card) {}

    public CardDebts {
        Objects.requireNonNull(openInvoices, "openInvoices");
        Objects.requireNonNull(asOf, "asOf");
        upcomingEvents = List.copyOf(upcomingEvents);
    }

    /** @return nulo sem cartão ativo acompanhado */
    public static CardDebts of(List<CardPosition> positions, LocalDate today, Currency currency) {
        if (positions.isEmpty()) {
            return null;
        }
        Money zero = Money.zero(currency);
        Money open = zero;
        LocalDate nextDate = null;
        Money nextAmount = zero;
        LocalDate horizon = today.plusDays(HORIZON_DAYS);
        List<Ranked> events = new ArrayList<>();

        for (int order = 0; order < positions.size(); order++) {
            CardPosition position = positions.get(order);
            CardLabel card = new CardLabel(position.cardId().value(), position.nickname(), position.last4());
            for (InvoiceStatement statement : position.statements()) {
                if (statement.status() == InvoiceStatus.FUTURE) {
                    continue;
                }
                Money remaining = statement.remaining();
                open = open.plus(remaining);
                LocalDate due = statement.dates().dueDate();
                YearMonth dueMonth = statement.dates().dueMonth();

                if (remaining.isPositive()) {
                    if (nextDate == null || due.isBefore(nextDate)) {
                        nextDate = due;
                        nextAmount = remaining;
                    }
                    else if (due.equals(nextDate)) {
                        nextAmount = nextAmount.plus(remaining);
                    }
                }

                if (statement.status() == InvoiceStatus.OPEN) {
                    if (!statement.amount().isPositive()) {
                        continue;
                    }
                    if (!statement.dates().closingDate().isAfter(horizon)) {
                        events.add(new Ranked(order, new UpcomingEvent(statement.dates().closingDate(),
                                UpcomingEventKind.INVOICE_CLOSING, UpcomingEventStatus.PARTIAL, dueMonth,
                                statement.amount(), card)));
                    }
                    if (remaining.isPositive() && !due.isAfter(horizon)) {
                        events.add(new Ranked(order, new UpcomingEvent(due, UpcomingEventKind.INVOICE_DUE,
                                UpcomingEventStatus.PARTIAL, dueMonth, remaining, card)));
                    }
                }
                else if (remaining.isPositive() && !due.isAfter(horizon)) {
                    events.add(new Ranked(order, new UpcomingEvent(due, UpcomingEventKind.INVOICE_DUE,
                            statement.status() == InvoiceStatus.OVERDUE ? UpcomingEventStatus.OVERDUE
                                    : UpcomingEventStatus.CLOSED, dueMonth, remaining, card)));
                }
            }
        }

        events.sort(Comparator.comparing((Ranked ranked) -> ranked.event().date())
                .thenComparing(ranked -> ranked.event().kind())
                .thenComparingInt(Ranked::order));
        return new CardDebts(positions.size(), open, nextDate == null ? null : new NextDue(nextDate, nextAmount),
                events.stream().map(Ranked::event).toList(), today);
    }

    private record Ranked(int order, UpcomingEvent event) {}
}
