package com.smarthealthfinance.forecast.presentation.dto.response;

import com.smarthealthfinance.forecast.application.dto.ForecastViews;
import com.smarthealthfinance.forecast.application.dto.ForecastViews.SeriesIds;
import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.ForecastFreshness;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Respostas de leitura do Forecast (ADR-0032 §1). Só dados gravados no snapshot, com o envelope comum
 * ({@code state}, {@code freshness}, {@code algorithmVersion}, {@code asOf}); nenhum texto gerado. Schemas com o
 * prefixo {@code Forecast} (ADR-0010).
 */
public final class ForecastResponses {

    private ForecastResponses() {
    }

    /** @param computedAt último recálculo do snapshot; nulo se nunca */
    public record ForecastFreshnessDto(String state, Instant computedAt, LocalDate asOf) {

        static ForecastFreshnessDto of(ForecastFreshness freshness) {
            return new ForecastFreshnessDto(freshness.state().name(), freshness.computedAt(), freshness.asOf());
        }
    }

    public record ForecastRangeDto(MoneyDto low, MoneyDto high) {}

    /**
     * @param month mês da amostra ou de fechamento da fatura; nulo numa ocorrência
     * @param refId transação (ocorrência) ou cartão (fatura de base); nulo numa amostra mensal
     * @param value valor da amostra; nulo quando só o id importa
     */
    public record ForecastEvidenceDto(String kind, String month, UUID refId, MoneyDto value) {

        static ForecastEvidenceDto of(Evidence evidence) {
            return new ForecastEvidenceDto(evidence.kind().name(),
                    evidence.month() == null ? null : evidence.month().toString(), evidence.refId(),
                    money(evidence.value()));
        }
    }

    /**
     * Valor exposto pelo Forecast (ADR-0030 §1). {@code value = factPart + estimatedPart}; {@code basis} é
     * {@code FACT} ou {@code ESTIMATED}; {@code partial} só em fato (ex.: fatura aberta).
     */
    public record ForecastValueDto(String basis, MoneyDto value, MoneyDto factPart, MoneyDto estimatedPart,
                                   ForecastRangeDto range, boolean partial, String confidence,
                                   List<String> limitations, List<ForecastEvidenceDto> evidence) {

        static ForecastValueDto of(Projected projected) {
            return projected == null ? null : new ForecastValueDto(projected.basis().name(), money(projected.value()),
                    money(projected.factPart()), money(projected.estimatedPart()),
                    new ForecastRangeDto(money(projected.low()), money(projected.high())), projected.partial(),
                    projected.confidence().name(), projected.limitations().stream().map(Enum::name).toList(),
                    projected.evidence().stream().map(ForecastEvidenceDto::of).toList());
        }
    }

    public record ForecastChannelDto(String kind, UUID id) {

        public static ForecastChannelDto of(Channel channel) {
            return new ForecastChannelDto(channel.kind().name(), channel.id());
        }
    }

    // --- /month ---

    /** @param seriesId id da série recorrente (para o detalhe); nulo se a série não existe mais */
    public record ForecastPendingOccurrenceDto(UUID seriesId, String direction, String label, LocalDate date,
                                               MoneyDto expected, ForecastRangeDto range, String confidence,
                                               ForecastChannelDto channel) {}

    /** @param baseMonths meses fechados usados no variável restante; 0 = sem estimativa */
    public record ForecastMonthDto(String month, LocalDate asOf, int baseMonths, ForecastValueDto expense,
                                   ForecastValueDto income, ForecastValueDto net,
                                   List<ForecastPendingOccurrenceDto> pending) {

        static ForecastMonthDto of(ForecastViews.Month view) {
            MonthProjection m = view.month();
            SeriesIds ids = view.ids();
            return new ForecastMonthDto(m.month().toString(), m.asOf(), m.baseMonths(), ForecastValueDto.of(m.expense()),
                    ForecastValueDto.of(m.income()), ForecastValueDto.of(m.net()),
                    m.pending().stream().map(p -> new ForecastPendingOccurrenceDto(ids.of(p.direction(), p.key()),
                            p.direction().name(), p.label(), p.date(), money(p.expected()),
                            range(p.range()), p.confidence().name(), ForecastChannelDto.of(p.channel()))).toList());
        }
    }

    /** @param month nulo sem snapshot ou sem transações */
    public record ForecastMonthResponse(String state, ForecastFreshnessDto freshness, String algorithmVersion,
                                        LocalDate asOf, ForecastMonthDto month) {

        public static ForecastMonthResponse of(ForecastViews.View<ForecastViews.Month> view) {
            return new ForecastMonthResponse(view.state().name(), ForecastFreshnessDto.of(view.freshness()),
                    view.algorithmVersion(), view.asOf(), view.body() == null ? null : ForecastMonthDto.of(view.body()));
        }
    }

    // --- /next-income ---

    public record ForecastNextIncomeDto(UUID seriesId, String label, LocalDate date, ForecastChannelDto channel,
                                        ForecastValueDto amount) {}

    /** @param nextIncome nulo sem snapshot, sem histórico suficiente ou sem receita recorrente prevista */
    public record ForecastNextIncomeResponse(String state, ForecastFreshnessDto freshness, String algorithmVersion,
                                             LocalDate asOf, ForecastNextIncomeDto nextIncome) {

        public static ForecastNextIncomeResponse of(ForecastViews.View<ForecastViews.Income> view) {
            ForecastViews.Income body = view.body();
            return new ForecastNextIncomeResponse(view.state().name(), ForecastFreshnessDto.of(view.freshness()),
                    view.algorithmVersion(), view.asOf(), body == null ? null
                            : new ForecastNextIncomeDto(body.seriesId(), body.income().label(), body.income().date(),
                                    ForecastChannelDto.of(body.income().channel()),
                                    ForecastValueDto.of(body.income().amount())));
        }
    }

    // --- /upcoming ---

    /**
     * @param kind            {@code INVOICE_PARTIAL}, {@code INVOICE_OPEN}, {@code INVOICE_OVERDUE},
     *                        {@code RECURRING_INCOME} ou {@code RECURRING_EXPENSE}
     * @param seriesId        série recorrente; nulo nos eventos de fatura
     * @param value           fato nos eventos de fatura, estimativa nas séries
     * @param closingEstimate "≈ no fechamento" de uma fatura aberta; nulo nos demais
     */
    public record ForecastUpcomingEventDto(String kind, LocalDate date, UUID seriesId, String label,
                                           ForecastChannelDto channel, ForecastValueDto value,
                                           ForecastValueDto closingEstimate) {

        static ForecastUpcomingEventDto of(UpcomingEvent event, SeriesIds ids) {
            var direction = switch (event.kind()) {
                case RECURRING_INCOME -> com.smarthealthfinance.forecast.domain.enums.SeriesDirection.INCOME;
                case RECURRING_EXPENSE -> com.smarthealthfinance.forecast.domain.enums.SeriesDirection.EXPENSE;
                default -> null;
            };
            return new ForecastUpcomingEventDto(event.kind().name(), event.date(),
                    direction == null ? null : ids.of(direction, event.key()), event.label(),
                    ForecastChannelDto.of(event.channel()), ForecastValueDto.of(event.value()),
                    ForecastValueDto.of(event.closingEstimate()));
        }
    }

    /** @param events a agenda de 30 dias, já ordenada; vazia sem snapshot */
    public record ForecastUpcomingResponse(String state, ForecastFreshnessDto freshness, String algorithmVersion,
                                           LocalDate asOf, List<ForecastUpcomingEventDto> events) {

        public static ForecastUpcomingResponse of(ForecastViews.View<ForecastViews.Upcoming> view) {
            ForecastViews.Upcoming body = view.body();
            return new ForecastUpcomingResponse(view.state().name(), ForecastFreshnessDto.of(view.freshness()),
                    view.algorithmVersion(), view.asOf(), body == null ? List.of()
                            : body.events().stream().map(e -> ForecastUpcomingEventDto.of(e, body.ids())).toList());
        }
    }

    // --- /invoices ---

    /**
     * @param kind  {@code OPEN}, {@code NEXT} ou {@code FUTURE}
     * @param total {@code factPart} é o parcial (aberta) ou o comprometido (futuras); {@code estimatedPart} são as
     *              novas compras pelo padrão
     */
    public record ForecastInvoiceDto(UUID cardId, String nickname, String kind, String closingMonth, String dueMonth,
                                     LocalDate closingDate, LocalDate dueDate, ForecastValueDto total) {

        static ForecastInvoiceDto of(CardInvoiceProjection invoice) {
            return new ForecastInvoiceDto(invoice.card().id(), invoice.nickname(), invoice.kind().name(),
                    invoice.closingMonth().toString(), invoice.dueMonth().toString(), invoice.closingDate(),
                    invoice.dueDate(), ForecastValueDto.of(invoice.total()));
        }
    }

    /** @param invoices vazia sem snapshot */
    public record ForecastInvoicesResponse(String state, ForecastFreshnessDto freshness, String algorithmVersion,
                                           LocalDate asOf, List<ForecastInvoiceDto> invoices) {

        public static ForecastInvoicesResponse of(ForecastViews.View<ForecastViews.Invoices> view) {
            return new ForecastInvoicesResponse(view.state().name(), ForecastFreshnessDto.of(view.freshness()),
                    view.algorithmVersion(), view.asOf(), view.body() == null ? List.of()
                            : view.body().invoices().stream().map(ForecastInvoiceDto::of).toList());
        }
    }

    // --- /cash-flow ---

    public record ForecastBalancePointDto(LocalDate date, MoneyDto central, MoneyDto low, MoneyDto high) {

        static ForecastBalancePointDto of(CashFlowProjection.BalancePoint point) {
            return new ForecastBalancePointDto(point.date(), money(point.central()), money(point.low()),
                    money(point.high()));
        }
    }

    public record ForecastTroughDto(ForecastBalancePointDto lowestCentral, ForecastBalancePointDto lowestLowBound) {

        static ForecastTroughDto of(CashFlowProjection.Trough trough) {
            return new ForecastTroughDto(ForecastBalancePointDto.of(trough.lowestCentral()),
                    ForecastBalancePointDto.of(trough.lowestLowBound()));
        }
    }

    /** As entradas e saídas são só dos dias projetados; {@code closing} é o saldo final do mês, com a faixa. */
    public record ForecastCashFlowMonthDto(String month, MoneyDto opening, MoneyDto inflowFact,
                                           MoneyDto inflowEstimated, MoneyDto outflowFact, MoneyDto outflowEstimated,
                                           ForecastValueDto closing) {}

    public record ForecastCashFlowDto(LocalDate from, LocalDate to, List<ForecastCashFlowMonthDto> months,
                                      ForecastTroughDto trough, ForecastTroughDto troughNext30, String confidence,
                                      List<String> limitations, List<ForecastEvidenceDto> evidence) {

        static ForecastCashFlowDto of(CashFlowProjection flow) {
            return new ForecastCashFlowDto(flow.from(), flow.to(), flow.months().stream()
                    .map(m -> new ForecastCashFlowMonthDto(m.month().toString(), money(m.opening()),
                            money(m.inflowFact()), money(m.inflowEstimated()), money(m.outflowFact()),
                            money(m.outflowEstimated()), ForecastValueDto.of(m.closing()))).toList(),
                    ForecastTroughDto.of(flow.trough()), ForecastTroughDto.of(flow.troughNext30()),
                    flow.confidence().name(), flow.limitations().stream().map(Enum::name).toList(),
                    flow.evidence().stream().map(ForecastEvidenceDto::of).toList());
        }
    }

    /** @param cashFlow nulo sem snapshot ou sem histórico suficiente */
    public record ForecastCashFlowResponse(String state, ForecastFreshnessDto freshness, String algorithmVersion,
                                           LocalDate asOf, ForecastCashFlowDto cashFlow) {

        public static ForecastCashFlowResponse of(ForecastViews.View<ForecastViews.CashFlow> view) {
            return new ForecastCashFlowResponse(view.state().name(), ForecastFreshnessDto.of(view.freshness()),
                    view.algorithmVersion(), view.asOf(),
                    view.body() == null ? null : ForecastCashFlowDto.of(view.body().cashFlow()));
        }
    }

    // --- helpers ---

    public static MoneyDto money(Money money) {
        return money == null ? null
                : MoneyDto.of(new BigDecimal(money.toPlainString()), money.currency().getCurrencyCode());
    }

    public static ForecastRangeDto range(Range range) {
        return new ForecastRangeDto(money(range.min()), money(range.max()));
    }
}
