package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;
import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InvoiceLine;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.forecast.domain.enums.InvoiceProjectionKind;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Faturas de cartão projetadas (ADR-0030 §3.5).
 * <ul>
 *   <li><b>aberta</b>: {@code fechamento estimado = parcial (FACT) + novas compras até o fechamento (ESTIMATED)};
 *   as novas compras são a mediana das {@value #BASE_INVOICES} últimas faturas fechadas (mínimo
 *   {@value #MIN_BASE_INVOICES}), contando só as compras sem parcela entre o dia seguinte ao equivalente a hoje e o
 *   fechamento;</li>
 *   <li><b>próxima e futuras</b>, até o fim do horizonte: {@code comprometido (FACT, parcelas) + novas compras
 *   (ESTIMATED)}; as novas compras são a mediana das mesmas faturas fechadas <b>sem as linhas de parcela</b>, menos os
 *   créditos.</li>
 * </ul>
 * Com menos de {@value #MIN_BASE_INVOICES} faturas fechadas acompanhadas (ou histórico global insuficiente), não há
 * estimativa de novas compras: só o fato, com {@code PARTIAL_CARD_HISTORY} (ou {@code INSUFFICIENT_HISTORY}).
 */
final class CardInvoiceProjector {

    static final int BASE_INVOICES = 3;
    static final int MIN_BASE_INVOICES = 2;

    private CardInvoiceProjector() {
    }

    /**
     * @param payments saídas de caixa por fatura (ADR-0030 §3.4): as projetadas, mais as fechadas ainda em aberto
     */
    record Result(List<CardInvoiceProjection> projections, List<InvoicePayment> payments) {}

    /** Pagamento de fatura no vencimento: o valor e a data em que sai da conta. */
    record InvoicePayment(LocalDate date, Projected amount) {}

    static Result project(ProjectionContext ctx, LocalDate horizonEnd) {
        List<CardInvoiceProjection> projections = new ArrayList<>();
        List<InvoicePayment> payments = new ArrayList<>();
        for (CardPosition card : ctx.input().cards()) {
            projectCard(ctx, card, horizonEnd, projections, payments);
        }
        payments.sort(Comparator.comparing(InvoicePayment::date));
        return new Result(projections, payments);
    }

    private static void projectCard(ProjectionContext ctx, CardPosition card, LocalDate horizonEnd,
                                    List<CardInvoiceProjection> projections, List<InvoicePayment> payments) {
        LocalDate today = ctx.today();
        Channel channel = Channel.card(card.cardId().value());
        InvoiceStatement current = card.current();
        List<InvoiceStatement> base = closedBase(card);

        // Faturas fechadas ainda em aberto: fato que sai da conta no vencimento (ou assim que possível, se vencida).
        for (InvoiceStatement statement : card.statements()) {
            if (isClosed(statement.status()) && statement.remaining().isPositive()
                    && !statement.dates().dueDate().isAfter(horizonEnd)) {
                LocalDate date = statement.dates().dueDate().isAfter(today) ? statement.dates().dueDate()
                        : today.plusDays(1);
                payments.add(new InvoicePayment(date, Projected.fact(statement.remaining())));
            }
        }

        // Fatura aberta.
        Estimate open = openEstimate(ctx, channel, current, base);
        CardInvoiceProjection openProjection = invoice(ctx, channel, card, current.dates(), InvoiceProjectionKind.OPEN,
                current.amount(), true, open);
        projections.add(openProjection);
        if (!current.dates().dueDate().isAfter(horizonEnd)) {
            payments.add(new InvoicePayment(current.dates().dueDate(), openProjection.total()));
        }

        // Próxima e futuras, até o fim do horizonte.
        Estimate future = futureEstimate(ctx, card, base);
        boolean first = true;
        for (YearMonth closing = current.dates().closingMonth().plusMonths(1);; closing = closing.plusMonths(1)) {
            InvoiceStatement known = card.statement(closing).orElse(null);
            InvoiceDates dates = known != null ? known.dates() : derivedDates(current.dates(), closing);
            if (dates.dueDate().isAfter(horizonEnd)) {
                break;
            }
            Money committed = known != null ? known.amount()
                    : first ? card.nextInvoiceCommitted() : Money.zero(current.amount().currency());
            CardInvoiceProjection projection = invoice(ctx, channel, card, dates,
                    first ? InvoiceProjectionKind.NEXT : InvoiceProjectionKind.FUTURE, committed, false, future);
            projections.add(projection);
            payments.add(new InvoicePayment(dates.dueDate(), projection.total()));
            first = false;
        }
    }

    /** Estimativa de novas compras, ou o motivo de não haver. */
    private record Estimate(Money central, Range range, boolean highVariability, List<Evidence> evidence,
                            boolean available) {

        static Estimate unavailable() {
            return new Estimate(null, null, false, List.of(), false);
        }
    }

    private static CardInvoiceProjection invoice(ProjectionContext ctx, Channel channel, CardPosition card,
                                                 InvoiceDates dates, InvoiceProjectionKind kind, Money factPart,
                                                 boolean partial, Estimate estimate) {
        Projected total;
        if (!ctx.sufficient() || !estimate.available()) {
            List<Limitation> limitations = !ctx.sufficient()
                    ? ConfidenceRules.historyLimitations(ctx.historyMonths())
                    : List.of(Limitation.PARTIAL_CARD_HISTORY);
            total = Projected.fact(factPart, partial, limitations, List.of());
        }
        else {
            total = Projected.estimated(factPart, estimate.central(), estimate.range(),
                    ConfidenceRules.finalConfidence(
                            List.of(ConfidenceRules.sampleConfidence(ctx.historyMonths(), estimate.highVariability())),
                            ctx.historyMonths(), false),
                    ConfidenceRules.limitations(ctx.historyMonths(), estimate.highVariability(), false, false),
                    estimate.evidence());
        }
        return new CardInvoiceProjection(channel, card.nickname(), dates.closingMonth(), dates.dueMonth(),
                dates.closingDate(), dates.dueDate(), kind, total);
    }

    /** Novas compras da aberta: compras sem parcela depois do dia equivalente a hoje, nas faturas de base. */
    private static Estimate openEstimate(ProjectionContext ctx, Channel channel, InvoiceStatement current,
                                         List<InvoiceStatement> base) {
        if (base.size() < MIN_BASE_INVOICES) {
            return Estimate.unavailable();
        }
        long elapsed = ChronoUnit.DAYS.between(current.dates().periodStart(), ctx.today());
        List<Money> samples = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        for (InvoiceStatement statement : base) {
            InvoiceDates dates = statement.dates();
            LocalDate cut = dates.periodStart().plusDays(elapsed + 1);
            Money value = ctx.input().entries().stream()
                    .filter(e -> isCardPurchase(e, channel) && !e.day().isBefore(cut) && e.day().isBefore(dates.closingDate()))
                    .map(ForecastEntry::amount)
                    .reduce(ctx.zero(), Money::plus);
            samples.add(value);
            evidence.add(Evidence.invoice(channel.id(), dates.closingMonth(), value));
        }
        return estimate(samples, evidence);
    }

    /** Novas compras das futuras: as faturas de base sem as linhas de parcela, menos os créditos. */
    private static Estimate futureEstimate(ProjectionContext ctx, CardPosition card, List<InvoiceStatement> base) {
        List<Money> samples = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        for (InvoiceStatement statement : base) {
            List<InvoiceLine> lines = card.lines().get(statement.dates().closingMonth());
            if (lines == null) {
                continue;
            }
            Money value = newPurchases(lines, ctx.zero());
            samples.add(value);
            evidence.add(Evidence.invoice(card.cardId().value(), statement.dates().closingMonth(), value));
        }
        if (samples.size() < MIN_BASE_INVOICES) {
            return Estimate.unavailable();
        }
        return estimate(samples, evidence);
    }

    private static Estimate estimate(List<Money> samples, List<Evidence> evidence) {
        Money central = Statistics.atLeastZero(Statistics.median(samples));
        Range range = Statistics.atLeastZero(Statistics.rangeOf(samples));
        return new Estimate(central, range, Statistics.highVariability(central, range), evidence, true);
    }

    /** Compras sem parcela menos créditos (que chegam positivos e abatem a fatura). */
    static Money newPurchases(List<InvoiceLine> lines, Money zero) {
        Money total = zero;
        for (InvoiceLine line : lines) {
            if (line.kind() == InvoiceItemKind.CREDIT) {
                total = total.minus(line.net());
            }
            else if (!isInstallment(line)) {
                total = total.plus(line.net());
            }
        }
        return total;
    }

    static boolean isInstallment(InvoiceLine line) {
        return line.installmentCount() != null && line.installmentCount() > 1;
    }

    private static boolean isCardPurchase(ForecastEntry entry, Channel channel) {
        return entry.role() == CashFlowRole.EXPENSE && !entry.installment() && entry.channel().equals(channel);
    }

    /** As até 3 últimas faturas fechadas, da mais antiga para a mais recente. */
    private static List<InvoiceStatement> closedBase(CardPosition card) {
        List<InvoiceStatement> closed = card.statements().stream()
                .filter(s -> isClosed(s.status()))
                .sorted(Comparator.comparing(s -> s.dates().closingMonth()))
                .toList();
        return closed.subList(Math.max(0, closed.size() - BASE_INVOICES), closed.size());
    }

    static boolean isClosed(InvoiceStatus status) {
        return status != InvoiceStatus.FUTURE && status != InvoiceStatus.OPEN;
    }

    /**
     * Datas de uma fatura sem linha no razão, pelo ciclo da fatura atual: mesmos dias de fechamento e vencimento,
     * limitados ao tamanho do mês, e o mesmo deslocamento entre fechamento e vencimento (ADR-0025 §5).
     */
    static InvoiceDates derivedDates(InvoiceDates reference, YearMonth closingMonth) {
        int offset = (int) ChronoUnit.MONTHS.between(reference.closingMonth(), reference.dueMonth());
        YearMonth dueMonth = closingMonth.plusMonths(offset);
        LocalDate closing = closingMonth.atDay(Math.min(reference.closingDate().getDayOfMonth(),
                closingMonth.lengthOfMonth()));
        LocalDate due = dueMonth.atDay(Math.min(reference.dueDate().getDayOfMonth(), dueMonth.lengthOfMonth()));
        LocalDate start = closingMonth.minusMonths(1).atDay(Math.min(reference.closingDate().getDayOfMonth(),
                closingMonth.minusMonths(1).lengthOfMonth()));
        return new InvoiceDates(closingMonth, dueMonth, start, closing, due, InvoiceDatesSource.CALCULATED);
    }
}
