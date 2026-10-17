package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.health.application.port.HealthSnapshotReader;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.dto.InsightViews;
import com.smarthealthfinance.insights.application.exception.InsightNotFoundException;
import com.smarthealthfinance.insights.application.exception.RelatedTransactionNotFoundException;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.service.InsightInputLoader;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.InsightType;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.insights.domain.service.InsightRules;
import com.smarthealthfinance.insights.domain.valueobject.EvaluationPeriod;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.Merchant;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leituras da API de Insights (ADR-0023): lista com contagens, detalhe, destaques da Home e relacionados. Autoriza o
 * membro antes de validar; nunca gera nem recalcula — só lê o que a geração gravou (a freshness pode marcar o
 * Workspace, por isso as transações são de escrita).
 */
@Service
public class InsightQueries {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int ATTENTION = 2;
    public static final int CHANGES = 3;
    public static final int RELATED = 3;
    /** Meses fechados antes de P abaixo dos quais, sem insight ativo, a resposta é {@code INSUFFICIENT_HISTORY}. */
    public static final int MIN_HISTORY_MONTHS = InsightRules.MIN_AVERAGE_MONTHS;
    /** Teto da leitura dos ativos para destaques e relacionados (um Workspace tem dezenas, não milhares). */
    private static final int VISIBLE_LIMIT = 500;

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final InsightStore store;
    private final GetInsightsStatus status;
    private final AnalyticsReadModel analytics;
    private final HealthSnapshotReader health;
    private final OverviewReadModel overview;
    private final TransactionRepository transactions;
    private final InsightInputLoader loader;

    public InsightQueries(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, InsightStore store,
                          GetInsightsStatus status, AnalyticsReadModel analytics, HealthSnapshotReader health,
                          OverviewReadModel overview, TransactionRepository transactions,
                          InsightInputLoader loader) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.store = store;
        this.status = status;
        this.analytics = analytics;
        this.health = health;
        this.overview = overview;
        this.transactions = transactions;
        this.loader = loader;
    }

    /** Filtro de tipo da lista: os do catálogo ou {@code CARDS}, aceito e sempre vazio até o M8. */
    public record TypeFilter(InsightType type, boolean cards) {}

    @Transactional
    public InsightViews.Page list(UUID workspaceId, String importance, String type, String state, Integer page,
                                  Integer pageSize) {
        WorkspaceId id = authorize(workspaceId);
        Importance importanceFilter = parse(Importance.class, "importance", importance);
        TypeFilter typeFilter = type(type);
        InsightStatus statusFilter = state == null || state.isBlank() ? InsightStatus.ACTIVE : status(state);
        int pageNumber = page == null ? 0 : page;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (pageNumber < 0) {
            throw new InvalidValueException("page", "OUT_OF_RANGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidValueException("pageSize", "OUT_OF_RANGE");
        }

        InsightsFreshness freshness = status.freshness(id);
        EvaluationPeriod period = EvaluationPeriod.of(loader.today());
        InsightStore.ChipCounts counts = store.activeCounts(id, InsightRules.ALGORITHM_VERSION);
        List<Insight> items = typeFilter.cards() ? List.of()
                : store.findPage(id, InsightRules.ALGORITHM_VERSION, statusFilter, importanceFilter,
                        typeFilter.type(), Math.multiplyExact(pageNumber, size), size);
        long total = typeFilter.cards() ? 0 : store.count(id, InsightRules.ALGORITHM_VERSION, statusFilter,
                importanceFilter, typeFilter.type());
        return new InsightViews.Page(dataState(id, period, counts.all()), freshness, period(period), counts, items,
                pageNumber, size, total);
    }

    @Transactional
    public InsightViews.Detail detail(UUID workspaceId, UUID insightId) {
        WorkspaceId id = authorize(workspaceId);
        Insight insight = store.findById(id, insightId).orElseThrow(InsightNotFoundException::new);
        InsightsFreshness freshness = status.freshness(id);
        LocalDate today = loader.today();
        List<Insight> related = visible(id).stream()
                .filter(other -> !other.id().equals(insight.id()) && related(insight, other))
                .limit(RELATED)
                .toList();
        return new InsightViews.Detail(freshness, insight, related, dimension(id, insight, today),
                dataSources(id, insight, today));
    }

    @Transactional
    public InsightViews.Highlights highlights(UUID workspaceId) {
        WorkspaceId id = authorize(workspaceId);
        InsightsFreshness freshness = status.freshness(id);
        EvaluationPeriod period = EvaluationPeriod.of(loader.today());
        List<Insight> visible = visible(id);
        List<Insight> attention = visible.stream().filter(i -> i.importance() == Importance.IMPORTANT)
                .limit(ATTENTION).toList();
        List<InsightViews.Change> changes = visible.stream().filter(i -> i.content().changeLine() != null)
                .limit(CHANGES)
                .map(i -> new InsightViews.Change(i.id(), i, i.content().changeLine()))
                .toList();
        long important = visible.stream().filter(i -> i.importance() == Importance.IMPORTANT).count();
        return new InsightViews.Highlights(dataState(id, period, visible.size()), freshness, period(period),
                attention, changes, important);
    }

    /** Exatamente um de {@code transactionId} ou {@code dimension}. */
    @Transactional
    public InsightViews.Related related(UUID workspaceId, String transactionId, String dimension) {
        WorkspaceId id = authorize(workspaceId);
        boolean byTransaction = transactionId != null && !transactionId.isBlank();
        boolean byDimension = dimension != null && !dimension.isBlank();
        if (byTransaction == byDimension) {
            throw new InvalidValueException(byTransaction ? "dimension" : "transactionId", "INVALID");
        }
        Predicate<Insight> match = byTransaction ? transactionMatch(id, uuid(transactionId))
                : dimensionMatch(parse(HealthDimension.class, "dimension", dimension));
        InsightsFreshness freshness = status.freshness(id);
        return new InsightViews.Related(freshness, visible(id).stream().filter(match).limit(RELATED).toList());
    }

    private Predicate<Insight> transactionMatch(WorkspaceId id, UUID transactionId) {
        Transaction transaction = transactions.findById(id, new TransactionId(transactionId))
                .orElseThrow(RelatedTransactionNotFoundException::new);
        String category = transaction.category().map(c -> c.code().value()).orElse(null);
        String merchant = transaction.merchant().map(Merchant::key).orElse(null);
        String month = YearMonth.from(transaction.occurredOn()).toString();
        return insight -> {
            InsightRefs refs = insight.content().refs();
            return transactionId.equals(refs.transactionId())
                    || insight.detector() == InsightDetector.CATEGORY_SPIKE && category != null
                    && category.equals(refs.categoryCode()) && month.equals(insight.periodKey())
                    || insight.detector() == InsightDetector.NEW_RECURRING_MERCHANT && merchant != null
                    && merchant.equals(refs.merchantKey());
        };
    }

    private static Predicate<Insight> dimensionMatch(HealthDimension dimension) {
        return insight -> insight.content().refs().dimension() == dimension;
    }

    /** Mesmo período (ou sem período) e algum assunto em comum: categoria, comerciante ou fonte de receita. */
    private static boolean related(Insight insight, Insight other) {
        boolean samePeriod = insight.periodKey() == null || other.periodKey() == null
                || insight.periodKey().equals(other.periodKey());
        InsightRefs a = insight.content().refs();
        InsightRefs b = other.content().refs();
        return samePeriod && (shared(a.categoryCode(), b.categoryCode()) || shared(a.merchantKey(), b.merchantKey())
                || shared(a.sourceKey(), b.sourceKey()));
    }

    private static boolean shared(String a, String b) {
        return a != null && a.equals(b);
    }

    private InsightViews.DimensionScore dimension(WorkspaceId id, Insight insight, LocalDate today) {
        HealthDimension dimension = insight.content().refs().dimension();
        if (dimension == null) {
            return null;
        }
        YearMonth month = insight.detector().periodBound() && insight.periodKey() != null
                ? YearMonth.parse(insight.periodKey()) : EvaluationPeriod.of(today).month();
        Integer score = health.find(id, HealthCalculator.ALGORITHM_VERSION, month).stream()
                .flatMap(r -> r.dimensions().stream())
                .filter(d -> d.dimension() == dimension)
                .findFirst()
                .map(DimensionResult::score)
                .orElse(null);
        return new InsightViews.DimensionScore(dimension, score);
    }

    /** Origens (OFX, CSV, manual) do intervalo coberto pelas fontes do insight (ADR-0019 §5, ADR-0023 §4). */
    private Set<DataSource> dataSources(WorkspaceId id, Insight insight, LocalDate today) {
        List<SourceRef> sources = insight.content().evidence().sources();
        Optional<LocalDate> from = sources.stream().map(SourceRef::from).min(Comparator.naturalOrder());
        Optional<LocalDate> to = sources.stream().map(SourceRef::to).max(Comparator.naturalOrder());
        if (from.isEmpty() || to.isEmpty()) {
            return Set.of();
        }
        return overview.dataSources(id, OverviewPeriod.resolve(PeriodType.CUSTOM, from.get(), to.get(), today));
    }

    private List<Insight> visible(WorkspaceId id) {
        return store.findPage(id, InsightRules.ALGORITHM_VERSION, InsightStatus.ACTIVE, null, null, 0,
                VISIBLE_LIMIT);
    }

    /** {@code NO_DATA} sem lançamento; {@code INSUFFICIENT_HISTORY} sem insight ativo e com menos de 2 meses antes de P. */
    private InsightViews.DataState dataState(WorkspaceId id, EvaluationPeriod period, long active) {
        Optional<LocalDate> first = analytics.firstActivity(id);
        if (first.isEmpty()) {
            return InsightViews.DataState.NO_DATA;
        }
        long monthsBefore = Stream.iterate(YearMonth.from(first.get()), m -> m.isBefore(period.month()),
                m -> m.plusMonths(1)).count();
        return active == 0 && monthsBefore < MIN_HISTORY_MONTHS ? InsightViews.DataState.INSUFFICIENT_HISTORY
                : InsightViews.DataState.READY;
    }

    private static InsightViews.Period period(EvaluationPeriod period) {
        return new InsightViews.Period(period.month(), period.partial());
    }

    private WorkspaceId authorize(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return id;
    }

    private static TypeFilter type(String value) {
        if (value == null || value.isBlank()) {
            return new TypeFilter(null, false);
        }
        if ("CARDS".equals(value.strip())) {
            return new TypeFilter(null, true);
        }
        return new TypeFilter(parse(InsightType.class, "type", value), false);
    }

    private static InsightStatus status(String value) {
        InsightStatus parsed = parse(InsightStatus.class, "state", value);
        if (parsed != InsightStatus.ACTIVE && parsed != InsightStatus.DISMISSED) {
            throw new InvalidValueException("state", "INVALID");
        }
        return parsed;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip());
        }
        catch (IllegalArgumentException invalid) {
            throw new InvalidValueException(field, "INVALID");
        }
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(Objects.requireNonNull(value).strip());
        }
        catch (IllegalArgumentException invalid) {
            throw new InvalidValueException("transactionId", "INVALID");
        }
    }
}
