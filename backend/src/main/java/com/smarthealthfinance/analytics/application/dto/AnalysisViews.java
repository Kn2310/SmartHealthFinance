package com.smarthealthfinance.analytics.application.dto;

import com.smarthealthfinance.analytics.application.service.AnalysisContext;
import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.model.ComparisonPlan;
import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.analytics.domain.service.CashFlowComposition;
import com.smarthealthfinance.analytics.domain.service.ChangeBreakdown;
import com.smarthealthfinance.analytics.domain.service.IncomeSources;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Views das consultas da Análise (ADR-0015). Toda view carrega o {@link Meta}; com {@code NO_DATA} os blocos de
 * dados vêm nulos (a UI mostra o empty state); com {@code NO_ACTIVITY_IN_PERIOD}, zero é um fato real.
 */
public final class AnalysisViews {

    private AnalysisViews() {
    }

    public record Meta(AnalysisPeriod period, ComparisonPlan comparison, ProjectionFreshness freshness,
                       DataState state) {

        public static Meta of(AnalysisContext context) {
            return new Meta(context.period(), context.plan(), context.freshness(), context.state());
        }
    }

    /** Um mês do histórico; o mês analisado, se parcial, vai só até hoje. */
    public record MonthBar(YearMonth month, boolean partial, Money income, Money expense, Money refunds, Money net) {
    }

    /**
     * @param categoryCode nulo = "Sem categoria"
     * @param share        % do total do papel no período (1 casa)
     * @param refunds      reembolsos da categoria no período (só despesas; zero nas receitas)
     */
    public record CategoryLine(String categoryCode, Money amount, BigDecimal share, int transactionCount,
                               Money refunds, Variation vsPrevious, Variation vsAverage) {
    }

    public record Metric(Money amount, Variation vsPrevious, Variation vsAverage) {

        public static Metric of(AnalysisContext.Metric metric) {
            return new Metric(metric.amount(), metric.vsPrevious(), metric.vsAverage());
        }
    }

    public record Overview(Meta meta, Metric income, Metric expense, Metric refunds, Metric net,
                           BigDecimal savedShare, List<MonthBar> months, Money averageExpense,
                           CashFlowComposition composition, ChangeBreakdown changes,
                           List<CategoryLine> topCategories) {
    }

    /** @param amount acumulado até o dia (inclusive) */
    public record DayPoint(int day, Money amount) {
    }

    public record Spending(Meta meta, Metric total, Money refunds, Money dailyAverage, Money previousDailyAverage,
                           List<DayPoint> cumulative, List<DayPoint> previousCumulative,
                           List<ExpenseItem> largestExpenses, List<CategoryLine> categories) {
    }

    public record Income(Meta meta, Metric total, Money monthlyAverage, List<IncomeSources.Source> sources,
                         List<MonthBar> months) {
    }

    public record Categories(Meta meta, Money totalExpense, List<CategoryLine> categories) {
    }

    /** @param merchantKey nulo = comerciante não identificado */
    public record MerchantAmount(String merchantKey, String merchantName, Money amount, int transactionCount) {
    }

    public record CategoryMonth(YearMonth month, boolean partial, Money amount, int transactionCount) {
    }

    /** @param averageTicket valor ÷ quantidade de lançamentos (2 casas); nulo sem lançamentos */
    public record CategoryDetail(Meta meta, String categoryCode, String nature, Metric amount, int transactionCount,
                                 Money averageTicket, List<CategoryMonth> history, List<MerchantAmount> merchants) {
    }

    /**
     * @param categoryCode categoria de maior valor do comerciante no período (nula = sem categoria)
     * @param vsAverage    contra a média mensal do comerciante nos meses de comparação (zeros contam)
     */
    public record MerchantLine(String merchantKey, String merchantName, String categoryCode, int transactionCount,
                               Money amount, Variation vsPrevious, Variation vsAverage, LocalDate lastPurchaseOn) {
    }

    /** @param unidentified despesas sem comerciante identificado (fora do ranking) */
    public record Merchants(Meta meta, String sort, List<MerchantLine> merchants, Money unidentified) {
    }

    public record Evolution(Meta meta, List<MonthBar> months) {
    }
}
