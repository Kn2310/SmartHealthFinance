package com.smarthealthfinance.analytics.application.usecase;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.CategoryDetail;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.CategoryLine;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.CategoryMonth;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.DayPoint;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.MerchantAmount;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.MerchantLine;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.Meta;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.Metric;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.MonthBar;
import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.application.service.AnalysisCalculations;
import com.smarthealthfinance.analytics.application.service.AnalysisContext;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader.Query;
import com.smarthealthfinance.analytics.domain.enums.ComparisonBase;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.analytics.domain.service.CashFlowComposition;
import com.smarthealthfinance.analytics.domain.service.ChangeBreakdown;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.service.IncomeSources;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Queries da Análise (spec 05.3, ADR-0015), uma por aba. Todo cálculo é feito aqui, no backend, de forma
 * determinística sobre a projeção do ADR-0014: o frontend só formata. Nenhum texto gerado; nenhuma IA.
 * <p>
 * Autorização no Workspace antes de validar parâmetros (ADR-0003). Log e métrica só com ids, aba e estado.
 */
@Service
@Transactional(readOnly = true)
public class AnalysisQueries {

    /** Meses das barras da Visão geral, de Receitas e do histórico da categoria (layouts D-Analysis*). */
    public static final int HISTORY_MONTHS = 6;
    public static final int TOP_CATEGORIES = 4;
    public static final int LARGEST_EXPENSES = 5;
    public static final int CATEGORY_MERCHANTS = 5;
    public static final int MAX_EVOLUTION_MONTHS = 12;

    private static final Logger log = LoggerFactory.getLogger(AnalysisQueries.class);

    private final AnalysisContextLoader loader;
    private final AnalyticsReadModel readModel;
    private final CategoryRepository categories;
    private final MeterRegistry meters;

    public AnalysisQueries(AnalysisContextLoader loader, AnalyticsReadModel readModel, CategoryRepository categories,
                           MeterRegistry meters) {
        this.loader = loader;
        this.readModel = readModel;
        this.categories = categories;
        this.meters = meters;
    }

    // --- Visão geral ---

    public AnalysisViews.Overview overview(UUID workspaceId, Query query) {
        return timed("overview", workspaceId, () -> {
            AnalysisContext context = loader.load(workspaceId, query, HISTORY_MONTHS);
            Meta meta = Meta.of(context);
            if (!context.hasData()) {
                return new AnalysisViews.Overview(meta, null, null, null, null, null, null, null, null, null, null);
            }
            FlowLedger ledger = context.ledger();
            AnalysisContext.Metric income = context.metric(w -> ledger.total(CashFlowRole.INCOME, w));
            AnalysisContext.Metric expense = context.metric(w -> ledger.total(CashFlowRole.EXPENSE, w));
            AnalysisContext.Metric refunds = context.metric(w -> ledger.total(CashFlowRole.REFUND, w));
            AnalysisContext.Metric net = context.metric(ledger::net);

            List<CategoryLine> lines = AnalysisCalculations.categoryLines(context, CashFlowRole.EXPENSE);
            return new AnalysisViews.Overview(meta, Metric.of(income), Metric.of(expense), Metric.of(refunds),
                    Metric.of(net), FlowLedger.share(net.amount(), income.amount()),
                    AnalysisCalculations.monthBars(context, HISTORY_MONTHS),
                    context.average(w -> ledger.total(CashFlowRole.EXPENSE, w)),
                    CashFlowComposition.of(ledger, context.current()), changes(context),
                    lines.stream().limit(TOP_CATEGORIES).toList());
        });
    }

    /** Decomposição contra a base escolhida; nula se a base não tem dados. */
    private static ChangeBreakdown changes(AnalysisContext context) {
        if (!context.plan().selectedAvailable()) {
            return null;
        }
        List<AnalyticsPeriod> base = context.plan().selected() == ComparisonBase.PREVIOUS
                ? List.of(context.plan().previous())
                : context.plan().averageWindows();
        return ChangeBreakdown.of(context.ledger(), context.current(), base);
    }

    // --- Gastos ---

    public AnalysisViews.Spending spending(UUID workspaceId, Query query) {
        return timed("spending", workspaceId, () -> {
            AnalysisContext context = loader.load(workspaceId, query, 1);
            Meta meta = Meta.of(context);
            if (!context.hasData()) {
                return new AnalysisViews.Spending(meta, null, null, null, null, List.of(), List.of(), List.of(),
                        List.of());
            }
            FlowLedger ledger = context.ledger();
            AnalysisContext.Metric total = context.metric(w -> ledger.total(CashFlowRole.EXPENSE, w));
            AnalyticsPeriod previous = context.plan().previous();

            return new AnalysisViews.Spending(meta, Metric.of(total),
                    ledger.total(CashFlowRole.REFUND, context.current()),
                    ledger.divide(total.amount(), context.period().daysElapsed()),
                    previous == null ? null : ledger.divide(ledger.total(CashFlowRole.EXPENSE, previous),
                            previous.to().getDayOfMonth()),
                    cumulative(ledger, context.current()),
                    previous == null ? List.of() : cumulative(ledger, AnalyticsPeriod.of(YearMonth.from(previous.from()))),
                    readModel.largestExpenses(context.workspaceId(), context.current(), LARGEST_EXPENSES,
                            context.currency()),
                    AnalysisCalculations.categoryLines(context, CashFlowRole.EXPENSE));
        });
    }

    /** Gasto acumulado dia a dia, do dia 1 ao último dia da janela. */
    private static List<DayPoint> cumulative(FlowLedger ledger, AnalyticsPeriod window) {
        Map<LocalDate, Money> byDay = ledger.byDay(CashFlowRole.EXPENSE, window);
        List<DayPoint> points = new ArrayList<>();
        Money running = ledger.zero();
        for (LocalDate day = window.from(); !day.isAfter(window.to()); day = day.plusDays(1)) {
            running = running.plus(byDay.getOrDefault(day, ledger.zero()));
            points.add(new DayPoint(day.getDayOfMonth(), running));
        }
        return points;
    }

    // --- Receitas ---

    public AnalysisViews.Income income(UUID workspaceId, Query query) {
        return timed("income", workspaceId, () -> {
            AnalysisContext context = loader.load(workspaceId, query, IncomeSources.HISTORY_MONTHS);
            Meta meta = Meta.of(context);
            if (!context.hasData()) {
                return new AnalysisViews.Income(meta, null, null, List.of(), List.of());
            }
            FlowLedger ledger = context.ledger();
            Function<AnalyticsPeriod, Money> income = w -> ledger.total(CashFlowRole.INCOME, w);
            return new AnalysisViews.Income(meta, Metric.of(context.metric(income)), context.average(income),
                    IncomeSources.of(ledger, context.period()),
                    AnalysisCalculations.monthBars(context, HISTORY_MONTHS));
        });
    }

    // --- Categorias ---

    public AnalysisViews.Categories categories(UUID workspaceId, Query query) {
        return timed("categories", workspaceId, () -> {
            AnalysisContext context = loader.load(workspaceId, query, 1);
            Meta meta = Meta.of(context);
            if (!context.hasData()) {
                return new AnalysisViews.Categories(meta, null, List.of());
            }
            return new AnalysisViews.Categories(meta,
                    context.ledger().total(CashFlowRole.EXPENSE, context.current()),
                    AnalysisCalculations.categoryLines(context, CashFlowRole.EXPENSE));
        });
    }

    /**
     * Detalhe de uma categoria do catálogo (de despesa ou de receita).
     *
     * @throws InvalidValueException {@code categoryCode} {@code INVALID} fora do catálogo
     */
    public CategoryDetail category(UUID workspaceId, String categoryCode, Query query) {
        return timed("category", workspaceId, () -> {
            AnalysisContext context = loader.load(workspaceId, query, HISTORY_MONTHS);
            Category category = categories.findByCode(new CategoryCode(categoryCode))
                    .orElseThrow(() -> new InvalidValueException("categoryCode", "INVALID"));
            Meta meta = Meta.of(context);
            String code = category.code().value();
            if (!context.hasData()) {
                return new CategoryDetail(meta, code, category.nature().name(), null, 0, null, List.of(), List.of());
            }
            CashFlowRole role = category.nature() == CategoryNature.INCOME ? CashFlowRole.INCOME : CashFlowRole.EXPENSE;
            FlowLedger ledger = context.ledger();
            Function<AnalyticsPeriod, Money> amount = w -> ledger.byCategory(role, w).getOrDefault(code, ledger.zero());
            AnalysisContext.Metric metric = context.metric(amount);
            int count = ledger.countByCategory(role, context.current()).getOrDefault(code, 0);

            List<CategoryMonth> history = context.period().lastMonths(HISTORY_MONTHS).stream()
                    .map(month -> {
                        AnalyticsPeriod window = context.period().historyRange(month);
                        return new CategoryMonth(month,
                                month.equals(context.period().month()) && context.period().partial(),
                                amount.apply(window), ledger.countByCategory(role, window).getOrDefault(code, 0));
                    })
                    .toList();
            Map<String, String> names = ledger.merchantNames();
            Map<String, Integer> merchantCounts = countByMerchantIn(ledger, role, code, context.current());
            List<MerchantAmount> merchants = ledger.byMerchantIn(role, code, context.current()).entrySet().stream()
                    .limit(CATEGORY_MERCHANTS)
                    .map(e -> new MerchantAmount(e.getKey(), e.getKey() == null ? null : names.get(e.getKey()),
                            e.getValue(), merchantCounts.getOrDefault(e.getKey(), 0)))
                    .toList();

            return new CategoryDetail(meta, code, category.nature().name(), Metric.of(metric), count,
                    count == 0 ? null : ledger.divide(metric.amount(), count), history, merchants);
        });
    }

    private static Map<String, Integer> countByMerchantIn(FlowLedger ledger, CashFlowRole role, String category,
                                                          AnalyticsPeriod window) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        ledger.entries(role, window).stream()
                .filter(e -> Objects.equals(e.categoryCode(), category))
                .forEach(e -> counts.merge(e.merchantKey(), e.transactionCount(), Integer::sum));
        return counts;
    }

    // --- Comerciantes ---

    public enum MerchantSort { AMOUNT, COUNT }

    public AnalysisViews.Merchants merchants(UUID workspaceId, Query query, String sort) {
        return timed("merchants", workspaceId, () -> {
            AnalysisContext context = loader.load(workspaceId, query, 1);
            MerchantSort order = parseSort(sort);
            Meta meta = Meta.of(context);
            if (!context.hasData()) {
                return new AnalysisViews.Merchants(meta, order.name(), List.of(), null);
            }
            FlowLedger ledger = context.ledger();
            AnalyticsPeriod current = context.current();
            Map<String, String> names = ledger.merchantNames();
            Map<String, Integer> counts = ledger.countByMerchant(CashFlowRole.EXPENSE, current);

            List<MerchantLine> lines = ledger.byMerchant(CashFlowRole.EXPENSE, current).entrySet().stream()
                    .filter(e -> e.getKey() != null)
                    .map(e -> {
                        String key = e.getKey();
                        Function<AnalyticsPeriod, Money> amount = w -> ledger.byMerchant(CashFlowRole.EXPENSE, w)
                                .getOrDefault(key, ledger.zero());
                        List<FlowEntry> items = ledger.entries(CashFlowRole.EXPENSE, current).stream()
                                .filter(entry -> key.equals(entry.merchantKey())).toList();
                        return new MerchantLine(key, names.get(key), mainCategory(items),
                                counts.getOrDefault(key, 0), e.getValue(),
                                Variation.of(e.getValue(), context.previous(amount)),
                                Variation.of(e.getValue(), context.average(amount)),
                                items.stream().map(FlowEntry::day).max(Comparator.naturalOrder()).orElse(null));
                    })
                    .sorted(order == MerchantSort.COUNT
                            ? Comparator.comparingInt(MerchantLine::transactionCount).reversed()
                                    .thenComparing(MerchantLine::merchantKey)
                            : Comparator.comparing((MerchantLine line) -> line.amount().amount()).reversed()
                                    .thenComparing(MerchantLine::merchantKey))
                    .toList();

            Money unidentified = ledger.byMerchant(CashFlowRole.EXPENSE, current).getOrDefault(null, ledger.zero());
            return new AnalysisViews.Merchants(meta, order.name(), lines, unidentified);
        });
    }

    private static MerchantSort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return MerchantSort.AMOUNT;
        }
        try {
            return MerchantSort.valueOf(sort.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException invalid) {
            throw new InvalidValueException("sort", "INVALID");
        }
    }

    private static String mainCategory(List<FlowEntry> items) {
        Map<String, Money> byCategory = new java.util.LinkedHashMap<>();
        items.forEach(e -> byCategory.merge(e.categoryCode(), e.amount(), Money::plus));
        return byCategory.entrySet().stream()
                .sorted((a, b) -> {
                    int byAmount = b.getValue().amount().compareTo(a.getValue().amount());
                    if (byAmount != 0) {
                        return byAmount;
                    }
                    return Objects.compare(a.getKey(), b.getKey(), Comparator.nullsLast(Comparator.naturalOrder()));
                })
                .findFirst()
                // Chave nula = "Sem categoria": Optional.map devolve vazio, e o resultado é nulo de propósito.
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // --- Evolução ---

    /** @param months quantos meses até o período (inclusive), 1–12; padrão 6 */
    public AnalysisViews.Evolution evolution(UUID workspaceId, Query query, Integer months) {
        return timed("evolution", workspaceId, () -> {
            int count = months == null ? HISTORY_MONTHS : months;
            AnalysisContext context = loader.load(workspaceId, query, Math.max(1, Math.min(count, MAX_EVOLUTION_MONTHS)));
            if (count < 1 || count > MAX_EVOLUTION_MONTHS) {
                throw new InvalidValueException("months", "OUT_OF_RANGE");
            }
            Meta meta = Meta.of(context);
            List<MonthBar> bars = context.hasData() ? AnalysisCalculations.monthBars(context, count) : List.of();
            return new AnalysisViews.Evolution(meta, bars);
        });
    }

    // --- infraestrutura ---

    private <T> T timed(String view, UUID workspaceId, Supplier<T> query) {
        Timer.Sample sample = Timer.start(meters);
        T result = query.get();
        String state = stateOf(result);
        sample.stop(Timer.builder("shf.analytics.query").tag("view", view).tag("state", state).register(meters));
        log.info("Analytics served workspaceId={} view={} state={}", workspaceId, view, state);
        return result;
    }

    private static String stateOf(Object view) {
        return switch (view) {
            case AnalysisViews.Overview v -> v.meta().state().name();
            case AnalysisViews.Spending v -> v.meta().state().name();
            case AnalysisViews.Income v -> v.meta().state().name();
            case AnalysisViews.Categories v -> v.meta().state().name();
            case CategoryDetail v -> v.meta().state().name();
            case AnalysisViews.Merchants v -> v.meta().state().name();
            case AnalysisViews.Evolution v -> v.meta().state().name();
            default -> "UNKNOWN";
        };
    }
}
