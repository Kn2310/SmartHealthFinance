package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.analytics.domain.enums.ComparisonBase;
import com.smarthealthfinance.analytics.domain.model.ComparisonPlan;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.model.HealthWindow;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.insights.domain.model.SeriesPoint;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * O que todos os detectores compartilham (ADR-0021 §1/§3), calculado uma vez: período P, base de comparação no mesmo
 * recorte de dias (ADR-0015 §2), referência R (despesa média da janela W do ADR-0017), participação sem categoria e
 * confiança.
 */
final class DetectionContext {

    static final String UNCATEGORIZED_NAME = "Sem categoria";
    static final String UNKNOWN_MERCHANT = "Comerciante não identificado";

    private final InsightInput input;
    private final AnalysisPeriod period;
    private final ComparisonPlan plan;
    private final HealthWindow window;
    private final Money reference;
    private final BigDecimal uncategorizedShare;
    private final Map<String, String> merchantNames;

    DetectionContext(InsightInput input) {
        this.input = input;
        this.period = input.period().period();
        this.plan = ComparisonPlan.of(period, ComparisonBase.AVERAGE, InsightRules.AVERAGE_MONTHS,
                input.firstActivity());
        this.window = HealthWindow.of(period, input.firstActivity());
        FlowLedger ledger = input.ledger();
        this.reference = window.isEmpty() ? null
                : ledger.divide(ledger.total(CashFlowRole.EXPENSE, window.range()), window.size());
        this.uncategorizedShare = uncategorizedShare(ledger);
        this.merchantNames = ledger.merchantNames();
    }

    InsightInput input() {
        return input;
    }

    FlowLedger ledger() {
        return input.ledger();
    }

    AnalysisPeriod period() {
        return period;
    }

    YearMonth month() {
        return period.month();
    }

    String key() {
        return period.month().toString();
    }

    boolean partial() {
        return period.partial();
    }

    LocalDate today() {
        return input.period().today();
    }

    Optional<LocalDate> firstActivity() {
        return input.firstActivity();
    }

    ComparisonPlan plan() {
        return plan;
    }

    int monthsUsed() {
        return plan.monthsUsed();
    }

    /** R: despesa média mensal da janela W; nula sem mês fechado. */
    Money reference() {
        return reference;
    }

    HealthWindow window() {
        return window;
    }

    Money zero() {
        return input.ledger().zero();
    }

    /** "neste mês" / "em setembro". */
    String inPeriod() {
        return InsightTexts.inPeriod(month(), partial());
    }

    String categoryName(String code) {
        return code == null ? UNCATEGORIZED_NAME : input.categoryNames().getOrDefault(code, code);
    }

    String merchantName(String key) {
        return key == null ? UNKNOWN_MERCHANT : merchantNames.getOrDefault(key, key);
    }

    /** Confiança (ADR-0021 §3) com {@code months} meses de base. */
    Confidence confidence(int months) {
        boolean dayOk = !partial() || period.daysElapsed() >= InsightRules.CONFIDENCE_HIGH_FROM_DAY;
        if (months >= InsightRules.CONFIDENCE_HIGH_MONTHS
                && uncategorizedShare.compareTo(InsightRules.CONFIDENCE_HIGH_UNCATEGORIZED) <= 0 && dayOk) {
            return Confidence.HIGH;
        }
        if (months >= InsightRules.CONFIDENCE_MEDIUM_MONTHS
                && uncategorizedShare.compareTo(InsightRules.CONFIDENCE_MEDIUM_UNCATEGORIZED) <= 0) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    Confidence confidence() {
        return confidence(monthsUsed());
    }

    /** Recorte de dias da base: "1–22" no mês parcial; vazio no fechado. */
    String dayCut() {
        return partial() ? " (1–" + period.daysElapsed() + ")" : "";
    }

    /** Meses da média, como {@code abr–ago}. */
    String averageRange() {
        return InsightTexts.range(plan.averageMonths());
    }

    /** Intervalo de datas coberto pela base e por P. */
    AnalyticsPeriod comparedRange() {
        return new AnalyticsPeriod(plan.earliestDay(period.from()), period.to());
    }

    /** Limitações comuns dos detectores comparativos. */
    List<Limitation> limitations(boolean comparative) {
        List<Limitation> limitations = new ArrayList<>();
        if (partial()) {
            limitations.add(partialMonth());
        }
        if (comparative && monthsUsed() < InsightRules.CONFIDENCE_MEDIUM_MONTHS) {
            limitations.add(new Limitation(LimitationCode.SHORT_HISTORY, "A média usa só "
                    + InsightTexts.count(monthsUsed(), "mês", "meses") + "."));
        }
        if (comparative && uncategorizedShare.compareTo(InsightRules.CONFIDENCE_HIGH_UNCATEGORIZED) > 0) {
            limitations.add(new Limitation(LimitationCode.UNCATEGORIZED,
                    InsightTexts.percent(uncategorizedShare, BigDecimal.ONE) + "% dos gastos estão sem categoria."));
        }
        return limitations;
    }

    Limitation partialMonth() {
        return new Limitation(LimitationCode.PARTIAL_MONTH, InsightTexts.capitalizedMonthName(month())
                + " ainda não terminou; o valor pode mudar até o dia " + period.daysInMonth() + ".");
    }

    /** Série dos 6 meses até P (o próprio P até hoje), a partir do mês do primeiro lançamento. */
    List<SeriesPoint> series(Function<AnalyticsPeriod, Money> value) {
        List<SeriesPoint> series = new ArrayList<>();
        Optional<YearMonth> first = input.firstActivity().map(YearMonth::from);
        for (YearMonth month : period.lastMonths(6)) {
            if (first.isPresent() && month.isBefore(first.get())) {
                continue;
            }
            boolean current = month.equals(month());
            series.add(new SeriesPoint(month, value.apply(period.historyRange(month)).amount(),
                    current && partial()));
        }
        return series;
    }

    /** Placeholders "Em breve" de Objetivos (M10) e Copilot (M11), aprovados para D1/D2. */
    static List<InsightAction> comingSoon() {
        return List.of(InsightAction.comingSoon(ActionKind.CREATE_SAVINGS_GOAL, "Criar objetivo de economia"),
                InsightAction.comingSoon(ActionKind.ASK_COPILOT, "Perguntar ao Copilot"));
    }

    /** Participação (razão, 4 casas) da despesa sem categoria em P e na base; zero sem despesa. */
    private BigDecimal uncategorizedShare(FlowLedger ledger) {
        List<AnalyticsPeriod> ranges = new ArrayList<>(plan.averageWindows());
        ranges.add(period.range());
        BigDecimal uncategorized = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (AnalyticsPeriod range : ranges) {
            Map<String, Money> byCategory = ledger.byCategory(CashFlowRole.EXPENSE, range);
            Money none = byCategory.get(null);
            if (none != null) {
                uncategorized = uncategorized.add(none.amount());
            }
            total = total.add(ledger.total(CashFlowRole.EXPENSE, range).amount());
        }
        return total.signum() == 0 ? BigDecimal.ZERO : uncategorized.divide(total, 4, RoundingMode.HALF_EVEN);
    }
}
