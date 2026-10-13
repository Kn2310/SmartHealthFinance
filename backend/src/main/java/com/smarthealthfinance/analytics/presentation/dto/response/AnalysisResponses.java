package com.smarthealthfinance.analytics.presentation.dto.response;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.domain.model.ComparisonPlan;
import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.analytics.domain.service.CashFlowComposition;
import com.smarthealthfinance.analytics.domain.service.ChangeBreakdown;
import com.smarthealthfinance.analytics.domain.service.IncomeSources;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Contrato da API de Analytics (ADR-0015 §7). Dinheiro como {@code {amount: "string", currency}}; percentuais como
 * string decimal com 1 casa ({@code "32.0"}), nunca ponto flutuante. Os nomes começam com {@code Analysis} para não
 * colidir com schemas de outros módulos no OpenAPI (ADR-0010).
 * <p>
 * {@code state = NO_DATA}: blocos de dados nulos; {@code NO_ACTIVITY_IN_PERIOD}: zeros reais. Variações nulas =
 * base indisponível ({@code comparison}).
 */
public final class AnalysisResponses {

    private AnalysisResponses() {
    }

    // --- comuns ---

    public record AnalysisPeriodDto(String month, LocalDate from, LocalDate to, boolean partial, int daysElapsed,
                                    int daysInMonth) {

        static AnalysisPeriodDto of(AnalysisPeriod period) {
            return new AnalysisPeriodDto(period.month().toString(), period.from(), period.to(), period.partial(),
                    period.daysElapsed(), period.daysInMonth());
        }
    }

    public record AnalysisRangeDto(LocalDate from, LocalDate to) {
    }

    /**
     * @param months      meses usados na média (do mais antigo ao mais recente)
     * @param monthsUsed  quantos dos {@code requestedMonths} têm histórico
     * @param state       AVAILABLE (3+ meses), LIMITED (1–2) ou INSUFFICIENT_DATA
     */
    public record AnalysisAverageDto(int requestedMonths, int monthsUsed, List<String> months, String state) {
    }

    /** @param previous nulo se o mês anterior é anterior ao primeiro lançamento */
    public record AnalysisComparisonDto(String selected, AnalysisRangeDto previous, AnalysisAverageDto average) {

        static AnalysisComparisonDto of(ComparisonPlan plan) {
            return new AnalysisComparisonDto(plan.selected().name(),
                    plan.previous() == null ? null : new AnalysisRangeDto(plan.previous().from(), plan.previous().to()),
                    new AnalysisAverageDto(plan.requestedMonths(), plan.monthsUsed(),
                            plan.averageMonths().stream().map(YearMonth::toString).toList(),
                            plan.averageState().name()));
        }
    }

    public record AnalysisFreshnessDto(String state, Instant refreshedAt, int pendingEvents) {

        static AnalysisFreshnessDto of(ProjectionFreshness freshness) {
            return new AnalysisFreshnessDto(freshness.state().name(), freshness.refreshedAt(),
                    freshness.pendingEvents());
        }
    }

    /**
     * @param percent nulo quando a base é zero
     * @param trend   UP, DOWN, STABLE (|variação| &lt; 5%), NEW (base zero) ou GONE (atual zero)
     */
    public record AnalysisVariationDto(MoneyDto base, MoneyDto difference, String percent, String trend) {

        static AnalysisVariationDto of(Variation variation) {
            return variation == null ? null : new AnalysisVariationDto(money(variation.base()),
                    money(variation.difference()), decimal(variation.percent()), variation.trend().name());
        }
    }

    public record AnalysisMetricDto(MoneyDto amount, AnalysisVariationDto vsPrevious, AnalysisVariationDto vsAverage) {

        static AnalysisMetricDto of(AnalysisViews.Metric metric) {
            return metric == null ? null : new AnalysisMetricDto(money(metric.amount()),
                    AnalysisVariationDto.of(metric.vsPrevious()), AnalysisVariationDto.of(metric.vsAverage()));
        }
    }

    public record AnalysisMonthDto(String month, boolean partial, MoneyDto income, MoneyDto expense,
                                   MoneyDto refunds, MoneyDto net) {

        static List<AnalysisMonthDto> of(List<AnalysisViews.MonthBar> bars) {
            return bars == null ? null : bars.stream().map(bar -> new AnalysisMonthDto(bar.month().toString(),
                    bar.partial(), money(bar.income()), money(bar.expense()), money(bar.refunds()),
                    money(bar.net()))).toList();
        }
    }

    /** @param categoryCode nulo = "Sem categoria" */
    public record AnalysisCategoryDto(String categoryCode, MoneyDto amount, String share, int transactionCount,
                                      MoneyDto refunds, AnalysisVariationDto vsPrevious,
                                      AnalysisVariationDto vsAverage) {

        static List<AnalysisCategoryDto> of(List<AnalysisViews.CategoryLine> lines) {
            return lines == null ? null : lines.stream().map(line -> new AnalysisCategoryDto(line.categoryCode(),
                    money(line.amount()), decimal(line.share()), line.transactionCount(), money(line.refunds()),
                    AnalysisVariationDto.of(line.vsPrevious()), AnalysisVariationDto.of(line.vsAverage()))).toList();
        }
    }

    // --- Visão geral ---

    /**
     * @param group      CATEGORY, UNCATEGORIZED ou OTHERS (as demais categorias somadas)
     * @param categories quantas categorias a linha soma
     */
    public record AnalysisOutflowDto(String group, String categoryCode, MoneyDto amount, int categories) {
    }

    /** {@code inflows − Σ outflows = net}, exatamente. */
    public record AnalysisCompositionDto(MoneyDto income, MoneyDto refunds, MoneyDto inflows,
                                         List<AnalysisOutflowDto> outflows, MoneyDto net) {

        static AnalysisCompositionDto of(CashFlowComposition composition) {
            return composition == null ? null : new AnalysisCompositionDto(money(composition.income()),
                    money(composition.refunds()), money(composition.inflows()),
                    composition.outflows().stream().map(o -> new AnalysisOutflowDto(o.group().name(),
                            o.categoryCode(), money(o.amount()), o.categories())).toList(),
                    money(composition.net()));
        }
    }

    public record AnalysisMerchantChangeDto(String merchantKey, MoneyDto current, MoneyDto base, MoneyDto delta) {
    }

    /**
     * @param kind   INCOME, REFUNDS ou EXPENSE_CATEGORY
     * @param impact efeito no resultado (gastar mais = negativo)
     */
    public record AnalysisChangeDto(String kind, String categoryCode, MoneyDto current, MoneyDto base, MoneyDto delta,
                                    MoneyDto impact, List<AnalysisMerchantChangeDto> merchants) {
    }

    /** {@code Σ items.impact = netDelta}, exatamente; {@code base} = a base escolhida em {@code comparison}. */
    public record AnalysisChangesDto(MoneyDto currentNet, MoneyDto baseNet, MoneyDto netDelta,
                                     List<AnalysisChangeDto> items) {

        static AnalysisChangesDto of(ChangeBreakdown breakdown) {
            return breakdown == null ? null : new AnalysisChangesDto(money(breakdown.currentNet()),
                    money(breakdown.baseNet()), money(breakdown.netDelta()),
                    breakdown.items().stream().map(item -> new AnalysisChangeDto(item.kind().name(),
                            item.categoryCode(), money(item.current()), money(item.base()), money(item.delta()),
                            money(item.impact()), item.merchants().stream().map(m -> new AnalysisMerchantChangeDto(
                                    m.merchantKey(), money(m.current()), money(m.base()), money(m.delta()))).toList()))
                            .toList());
        }
    }

    /**
     * @param savedShare % da receita que não foi gasta (resultado ÷ receitas); nula sem receitas
     * @param months     6 meses até o período
     */
    public record AnalysisOverviewResponse(UUID workspaceId, String state, AnalysisPeriodDto period,
                                           AnalysisComparisonDto comparison, AnalysisFreshnessDto freshness,
                                           AnalysisMetricDto income, AnalysisMetricDto expense,
                                           AnalysisMetricDto refunds, AnalysisMetricDto net, String savedShare,
                                           List<AnalysisMonthDto> months, MoneyDto averageExpense,
                                           AnalysisCompositionDto composition, AnalysisChangesDto changes,
                                           List<AnalysisCategoryDto> topCategories) {

        public static AnalysisOverviewResponse from(UUID workspaceId, AnalysisViews.Overview view) {
            return new AnalysisOverviewResponse(workspaceId, view.meta().state().name(),
                    AnalysisPeriodDto.of(view.meta().period()), AnalysisComparisonDto.of(view.meta().comparison()),
                    AnalysisFreshnessDto.of(view.meta().freshness()), AnalysisMetricDto.of(view.income()),
                    AnalysisMetricDto.of(view.expense()), AnalysisMetricDto.of(view.refunds()),
                    AnalysisMetricDto.of(view.net()), decimal(view.savedShare()), AnalysisMonthDto.of(view.months()),
                    money(view.averageExpense()), AnalysisCompositionDto.of(view.composition()),
                    AnalysisChangesDto.of(view.changes()), AnalysisCategoryDto.of(view.topCategories()));
        }
    }

    // --- Gastos ---

    /** @param amount acumulado do dia 1 até {@code day} */
    public record AnalysisDayDto(int day, MoneyDto amount) {

        static List<AnalysisDayDto> of(List<AnalysisViews.DayPoint> points) {
            return points.stream().map(p -> new AnalysisDayDto(p.day(), money(p.amount()))).toList();
        }
    }

    public record AnalysisExpenseDto(UUID transactionId, LocalDate occurredOn, String description, String merchantName,
                                     String categoryCode, MoneyDto amount) {

        static List<AnalysisExpenseDto> of(List<ExpenseItem> items) {
            return items.stream().map(i -> new AnalysisExpenseDto(i.transactionId(), i.occurredOn(), i.description(),
                    i.merchantName(), i.categoryCode(), money(i.amount()))).toList();
        }
    }

    /**
     * @param cumulative         gasto acumulado do mês até hoje
     * @param previousCumulative gasto acumulado do mês anterior inteiro (vazio sem histórico)
     */
    public record AnalysisSpendingResponse(UUID workspaceId, String state, AnalysisPeriodDto period,
                                           AnalysisComparisonDto comparison, AnalysisFreshnessDto freshness,
                                           AnalysisMetricDto total, MoneyDto refunds, MoneyDto dailyAverage,
                                           MoneyDto previousDailyAverage, List<AnalysisDayDto> cumulative,
                                           List<AnalysisDayDto> previousCumulative,
                                           List<AnalysisExpenseDto> largestExpenses,
                                           List<AnalysisCategoryDto> categories) {

        public static AnalysisSpendingResponse from(UUID workspaceId, AnalysisViews.Spending view) {
            return new AnalysisSpendingResponse(workspaceId, view.meta().state().name(),
                    AnalysisPeriodDto.of(view.meta().period()), AnalysisComparisonDto.of(view.meta().comparison()),
                    AnalysisFreshnessDto.of(view.meta().freshness()), AnalysisMetricDto.of(view.total()),
                    money(view.refunds()), money(view.dailyAverage()), money(view.previousDailyAverage()),
                    AnalysisDayDto.of(view.cumulative()), AnalysisDayDto.of(view.previousCumulative()),
                    AnalysisExpenseDto.of(view.largestExpenses()), AnalysisCategoryDto.of(view.categories()));
        }
    }

    // --- Receitas ---

    /**
     * @param merchantKey nulo quando a fonte é a categoria
     * @param recurrence  RECURRING (3+ dos últimos 6 meses) ou OCCASIONAL
     * @param typicalDay  dia do mês mais frequente; só em RECURRING
     */
    public record AnalysisIncomeSourceDto(String merchantKey, String merchantName, String categoryCode,
                                          MoneyDto amount, String share, int transactionCount, String recurrence,
                                          Integer typicalDay, LocalDate lastReceivedOn) {

        static List<AnalysisIncomeSourceDto> of(List<IncomeSources.Source> sources) {
            return sources.stream().map(s -> new AnalysisIncomeSourceDto(s.merchantKey(), s.merchantName(),
                    s.categoryCode(), money(s.amount()), decimal(s.share()), s.transactionCount(),
                    s.recurrence().name(), s.typicalDay(), s.lastReceivedOn())).toList();
        }
    }

    public record AnalysisIncomeResponse(UUID workspaceId, String state, AnalysisPeriodDto period,
                                         AnalysisComparisonDto comparison, AnalysisFreshnessDto freshness,
                                         AnalysisMetricDto total, MoneyDto monthlyAverage,
                                         List<AnalysisIncomeSourceDto> sources, List<AnalysisMonthDto> months) {

        public static AnalysisIncomeResponse from(UUID workspaceId, AnalysisViews.Income view) {
            return new AnalysisIncomeResponse(workspaceId, view.meta().state().name(),
                    AnalysisPeriodDto.of(view.meta().period()), AnalysisComparisonDto.of(view.meta().comparison()),
                    AnalysisFreshnessDto.of(view.meta().freshness()), AnalysisMetricDto.of(view.total()),
                    money(view.monthlyAverage()), AnalysisIncomeSourceDto.of(view.sources()),
                    AnalysisMonthDto.of(view.months()));
        }
    }

    // --- Categorias ---

    public record AnalysisCategoriesResponse(UUID workspaceId, String state, AnalysisPeriodDto period,
                                             AnalysisComparisonDto comparison, AnalysisFreshnessDto freshness,
                                             MoneyDto totalExpense, List<AnalysisCategoryDto> categories) {

        public static AnalysisCategoriesResponse from(UUID workspaceId, AnalysisViews.Categories view) {
            return new AnalysisCategoriesResponse(workspaceId, view.meta().state().name(),
                    AnalysisPeriodDto.of(view.meta().period()), AnalysisComparisonDto.of(view.meta().comparison()),
                    AnalysisFreshnessDto.of(view.meta().freshness()), money(view.totalExpense()),
                    AnalysisCategoryDto.of(view.categories()));
        }
    }

    public record AnalysisCategoryMonthDto(String month, boolean partial, MoneyDto amount, int transactionCount) {
    }

    public record AnalysisMerchantAmountDto(String merchantKey, String merchantName, MoneyDto amount,
                                            int transactionCount) {
    }

    /** @param averageTicket valor ÷ lançamentos (2 casas); nulo sem lançamentos */
    public record AnalysisCategoryDetailResponse(UUID workspaceId, String state, AnalysisPeriodDto period,
                                                 AnalysisComparisonDto comparison, AnalysisFreshnessDto freshness,
                                                 String categoryCode, String nature, AnalysisMetricDto amount,
                                                 int transactionCount, MoneyDto averageTicket,
                                                 List<AnalysisCategoryMonthDto> history,
                                                 List<AnalysisMerchantAmountDto> merchants) {

        public static AnalysisCategoryDetailResponse from(UUID workspaceId, AnalysisViews.CategoryDetail view) {
            return new AnalysisCategoryDetailResponse(workspaceId, view.meta().state().name(),
                    AnalysisPeriodDto.of(view.meta().period()), AnalysisComparisonDto.of(view.meta().comparison()),
                    AnalysisFreshnessDto.of(view.meta().freshness()), view.categoryCode(), view.nature(),
                    AnalysisMetricDto.of(view.amount()), view.transactionCount(), money(view.averageTicket()),
                    view.history().stream().map(h -> new AnalysisCategoryMonthDto(h.month().toString(), h.partial(),
                            money(h.amount()), h.transactionCount())).toList(),
                    view.merchants().stream().map(m -> new AnalysisMerchantAmountDto(m.merchantKey(),
                            m.merchantName(), money(m.amount()), m.transactionCount())).toList());
        }
    }

    // --- Comerciantes ---

    public record AnalysisMerchantDto(String merchantKey, String merchantName, String categoryCode,
                                      int transactionCount, MoneyDto amount, AnalysisVariationDto vsPrevious,
                                      AnalysisVariationDto vsAverage, LocalDate lastPurchaseOn) {
    }

    /** @param unidentified despesas sem comerciante identificado, fora do ranking */
    public record AnalysisMerchantsResponse(UUID workspaceId, String state, AnalysisPeriodDto period,
                                            AnalysisComparisonDto comparison, AnalysisFreshnessDto freshness,
                                            String sort, List<AnalysisMerchantDto> merchants, MoneyDto unidentified) {

        public static AnalysisMerchantsResponse from(UUID workspaceId, AnalysisViews.Merchants view) {
            return new AnalysisMerchantsResponse(workspaceId, view.meta().state().name(),
                    AnalysisPeriodDto.of(view.meta().period()), AnalysisComparisonDto.of(view.meta().comparison()),
                    AnalysisFreshnessDto.of(view.meta().freshness()), view.sort(),
                    view.merchants().stream().map(m -> new AnalysisMerchantDto(m.merchantKey(), m.merchantName(),
                            m.categoryCode(), m.transactionCount(), money(m.amount()),
                            AnalysisVariationDto.of(m.vsPrevious()), AnalysisVariationDto.of(m.vsAverage()),
                            m.lastPurchaseOn())).toList(),
                    money(view.unidentified()));
        }
    }

    // --- Evolução ---

    public record AnalysisEvolutionResponse(UUID workspaceId, String state, AnalysisPeriodDto period,
                                            AnalysisComparisonDto comparison, AnalysisFreshnessDto freshness,
                                            List<AnalysisMonthDto> months) {

        public static AnalysisEvolutionResponse from(UUID workspaceId, AnalysisViews.Evolution view) {
            return new AnalysisEvolutionResponse(workspaceId, view.meta().state().name(),
                    AnalysisPeriodDto.of(view.meta().period()), AnalysisComparisonDto.of(view.meta().comparison()),
                    AnalysisFreshnessDto.of(view.meta().freshness()), AnalysisMonthDto.of(view.months()));
        }
    }

    // --- formato ---

    static MoneyDto money(Money money) {
        return money == null ? null
                : MoneyDto.of(new BigDecimal(money.toPlainString()), money.currency().getCurrencyCode());
    }

    static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
