package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection.BalancePoint;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Detector de previsão da {@code insights-v3} (ADR-0032 §3, ADR-0030 §8): D15 {@code BALANCE_MAY_GO_NEGATIVE}. Os
 * números são os do snapshot do Forecast ({@code cashFlow.troughNext30}), lidos pela porta {@code ForecastReader}:
 * nenhum cálculo financeiro próprio, só comparação com zero e com o piso de R$ 100.
 * <p>
 * Identidade: sem período e com sujeito fixo ({@value #SUBJECT}), como o D9. O insight fica estável enquanto o risco
 * persiste, mesmo que o dia do menor limite baixo se desloque de um dia para o outro; quando o risco some, resolve, e
 * volta a ficar ativo (mesma identidade) se o risco reaparecer.
 */
final class ForecastDetectors {

    static final String SUBJECT = "next30";

    private ForecastDetectors() {
    }

    /**
     * D15: o limite baixo do saldo total projetado nos próximos 30 dias &lt; 0, com déficit ≥ R$ 100 e confiança do
     * fluxo de caixa ≥ {@code MEDIUM}. Importante quando o valor central também fica &lt; 0 na janela.
     */
    static List<InsightCandidate> balanceMayGoNegative(DetectionContext ctx) {
        ForecastResult forecast = ctx.input().forecast().orElse(null);
        if (forecast == null || forecast.cashFlow() == null
                || forecast.limitations().contains(Limitation.INSUFFICIENT_HISTORY)) {
            return List.of();
        }
        CashFlowProjection cashFlow = forecast.cashFlow();
        if (cashFlow.confidence() == com.smarthealthfinance.forecast.domain.enums.Confidence.LOW) {
            return List.of();
        }
        BalancePoint lowest = cashFlow.troughNext30().lowestLowBound();
        Money deficit = lowest.low().negate();
        if (lowest.low().amount().signum() >= 0
                || deficit.amount().compareTo(InsightRules.BALANCE_MIN_DEFICIT) < 0) {
            return List.of();
        }
        BalancePoint lowestCentral = cashFlow.troughNext30().lowestCentral();
        boolean centralNegative = lowestCentral.central().amount().signum() < 0;
        Confidence confidence = Confidence.valueOf(cashFlow.confidence().name());

        LocalDate date = lowest.date();
        LocalDate from = forecast.asOf();
        String title = "Seu saldo pode ficar negativo por volta de %s.".formatted(InsightTexts.dayMonth(date));
        String support = ("Pela projeção, o saldo total chega a %s em %s, com faixa de %s a %s. Estimativa com "
                + "confiança %s.").formatted(InsightTexts.money(lowest.central()), InsightTexts.dayMonth(date),
                InsightTexts.money(lowest.low()), InsightTexts.money(lowest.high()), confidenceName(confidence));

        List<KeyFigure> figures = new ArrayList<>(List.of(
                new KeyFigure("lowBound", "Menor saldo (limite baixo)", FigureKind.MONEY, lowest.low().amount(),
                        from, date),
                new KeyFigure("central", "Saldo central nesse dia", FigureKind.MONEY, lowest.central().amount(),
                        from, date),
                new KeyFigure("highBound", "Saldo no limite alto nesse dia", FigureKind.MONEY,
                        lowest.high().amount(), from, date),
                new KeyFigure("lowestCentral", "Menor saldo central dos próximos 30 dias", FigureKind.MONEY,
                        lowestCentral.central().amount(), from, lowestCentral.date())));

        String comparison = ("Projetamos o saldo total das suas contas dia a dia nos próximos 30 dias, a partir do "
                + "saldo de hoje, das recorrências, das faturas e do gasto variável típico. O limite baixo é o "
                + "pior cenário da faixa; o saldo central é o esperado.");
        List<com.smarthealthfinance.insights.domain.model.Limitation> limitations = new ArrayList<>();
        limitations.add(new com.smarthealthfinance.insights.domain.model.Limitation(
                LimitationCode.ESTIMATED_FORECAST,
                "É uma projeção estimada, com faixa e confiança %s; o saldo real pode ficar fora dela.".formatted(
                        confidenceName(confidence))));
        if (cashFlow.limitations().contains(Limitation.SHORT_HISTORY)) {
            limitations.add(new com.smarthealthfinance.insights.domain.model.Limitation(LimitationCode.SHORT_HISTORY,
                    "O histórico tem poucos meses fechados."));
        }
        if (cashFlow.limitations().contains(Limitation.UNCATEGORIZED)) {
            limitations.add(new com.smarthealthfinance.insights.domain.model.Limitation(LimitationCode.UNCATEGORIZED,
                    "Há muitos gastos sem categoria, o que reduz a confiança."));
        }

        InsightEvidence evidence = new InsightEvidence(figures, List.of(), movements(forecast, date), comparison,
                limitations, List.of(new SourceRef(InsightSource.FORECAST_CASH_FLOW, from, date)));
        InsightContent content = new InsightContent(title, support, "Saldo projetado", null, null, evidence,
                List.of(InsightAction.primary(ActionKind.VIEW_UPCOMING_EVENTS, "Ver próximos eventos", Map.of())),
                InsightRefs.none());
        return List.of(new InsightCandidate(InsightDetector.BALANCE_MAY_GO_NEGATIVE, null, SUBJECT, deficit, null,
                centralNegative, confidence, false, content));
    }

    /**
     * Os maiores movimentos até a data: o snapshot não guarda o movimento por dia, então usamos os próximos eventos
     * dele (faturas e ocorrências das séries em conta) com data até o dia do menor limite baixo, pelo maior valor, no
     * máximo {@value InsightRules#BALANCE_MOVEMENTS}. Ocorrência de série de cartão fica de fora: já está na fatura.
     * Entradas com sinal positivo, saídas com sinal negativo. O gasto variável estimado não é um evento.
     */
    private static List<Contributor> movements(ForecastResult forecast, LocalDate until) {
        return forecast.upcomingEvents().stream()
                .filter(e -> !e.date().isAfter(until))
                .filter(e -> !(e.kind() == UpcomingEventKind.RECURRING_EXPENSE && e.channel().isCard()))
                .sorted(Comparator.comparing((UpcomingEvent e) -> e.value().value().amount().abs()).reversed()
                        .thenComparing(UpcomingEvent::date).thenComparing(UpcomingEvent::label))
                .limit(InsightRules.BALANCE_MOVEMENTS)
                .map(e -> {
                    boolean income = e.kind() == UpcomingEventKind.RECURRING_INCOME;
                    BigDecimal amount = e.value().value().amount().abs();
                    return new Contributor(ContributorKind.FACTOR, e.kind().name(), e.label(),
                            income ? amount : amount.negate(), null, null, e.date());
                })
                .toList();
    }

    private static String confidenceName(Confidence confidence) {
        return switch (confidence) {
            case HIGH -> "alta";
            case MEDIUM -> "média";
            case LOW -> "baixa";
        };
    }
}
