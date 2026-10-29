package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.InvoiceProjectionKind;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.ForecastInput.InvoiceEventFact;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Próximos eventos dos 30 dias (ADR-0030 §3.3).
 * <ul>
 *   <li><b>fatos</b>: os eventos de fatura do ADR-0006 §18, sem mudança, vindos da entrada;</li>
 *   <li><b>estimados</b>: as ocorrências esperadas das séries elegíveis em {@code [hoje, hoje + 30]} ainda não
 *   realizadas, com valor e faixa; um evento de fatura parcial ganha o "≈ no fechamento" (3.5) sem trocar o fato.</li>
 * </ul>
 * Ordem: data, depois fatos antes de estimados, depois o maior valor (desempate: tipo e chave, para ser
 * determinístico). Sem histórico suficiente, só os fatos.
 */
final class UpcomingEventsProjector {

    static final int WINDOW_DAYS = 30;

    private UpcomingEventsProjector() {
    }

    static List<UpcomingEvent> project(ProjectionContext ctx, List<CardInvoiceProjection> invoices) {
        List<UpcomingEvent> events = new ArrayList<>();
        for (InvoiceEventFact fact : ctx.input().invoiceEvents()) {
            events.add(invoiceEvent(fact, invoices));
        }
        if (ctx.sufficient()) {
            LocalDate end = ctx.today().plusDays(WINDOW_DAYS);
            for (SeriesAssessment assessment : ctx.assessments()) {
                if (assessment.isEligible()) {
                    seriesEvents(ctx, assessment, end, events);
                }
            }
        }
        events.sort(Comparator.comparing(UpcomingEvent::date)
                .thenComparing(e -> e.value().basis() == Basis.FACT ? 0 : 1)
                .thenComparing((UpcomingEvent e) -> e.value().value().amount(), Comparator.reverseOrder())
                .thenComparing(UpcomingEvent::kind)
                .thenComparing(e -> e.key() == null ? "" : e.key())
                .thenComparing(e -> e.channel().id()));
        return events;
    }

    private static UpcomingEvent invoiceEvent(InvoiceEventFact fact, List<CardInvoiceProjection> invoices) {
        Projected closing = fact.kind() == UpcomingEventKind.INVOICE_PARTIAL
                ? invoices.stream()
                        .filter(i -> i.kind() == InvoiceProjectionKind.OPEN && i.card().id().equals(fact.cardId())
                                && i.closingMonth().equals(fact.closingMonth()) && i.total().isEstimated())
                        .map(CardInvoiceProjection::total).findFirst().orElse(null)
                : null;
        return new UpcomingEvent(fact.kind(), fact.date(), null, fact.cardNickname(), Channel.card(fact.cardId()),
                Projected.fact(fact.amount(), fact.kind() == UpcomingEventKind.INVOICE_PARTIAL, List.of(),
                        List.of(Evidence.invoice(fact.cardId(), fact.closingMonth(), fact.amount()))),
                closing);
    }

    private static void seriesEvents(ProjectionContext ctx, SeriesAssessment assessment, LocalDate end,
                                     List<UpcomingEvent> events) {
        RecurringSeries series = assessment.series();
        for (YearMonth month = ctx.month(); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
            if (month.equals(ctx.month()) && assessment.realizedThisMonth()) {
                continue;
            }
            LocalDate date = series.expectedDateIn(month);
            if (date.isBefore(ctx.today()) || date.isAfter(end)) {
                continue;
            }
            Projected value = Projected.estimated(ctx.zero(), series.effectiveAmount(), series.effectiveRange(),
                    ConfidenceRules.finalConfidence(List.of(series.effectiveConfidence()), ctx.historyMonths(),
                            false),
                    ConfidenceRules.historyLimitations(ctx.historyMonths()),
                    series.occurrences().stream().map(Evidence::occurrence).toList());
            UpcomingEventKind kind = series.direction() == SeriesDirection.INCOME
                    ? UpcomingEventKind.RECURRING_INCOME : UpcomingEventKind.RECURRING_EXPENSE;
            events.add(new UpcomingEvent(kind, date, series.key(), series.label(), series.channel(), value, null));
        }
    }
}
