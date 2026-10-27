package com.smarthealthfinance.insights.presentation.dto.response;

import com.smarthealthfinance.insights.application.dto.InsightViews;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightType;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import com.smarthealthfinance.insights.domain.service.InsightRules;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import java.math.BigDecimal;
import java.util.Currency;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Respostas da API de Insights (ADR-0023), nas convenções da Análise e do Health (ADR-0015/0019): dinheiro em
 * {@link MoneyDto}, números das evidências como string decimal com a unidade ({@code kind}), enums como string. Os
 * textos vêm prontos dos templates do ADR-0021; os campos estruturados existem para a UI montar links e gráficos.
 */
public final class InsightResponses {

    private InsightResponses() {
    }

    /** @param state {@code UP_TO_DATE}, {@code UPDATING} ou {@code NOT_BUILT} */
    public record InsightsFreshnessDto(String state, Instant generatedAt, Instant healthComputedAt) {

        static InsightsFreshnessDto of(InsightsFreshness freshness) {
            return new InsightsFreshnessDto(freshness.state().name(), freshness.generatedAt(),
                    freshness.healthComputedAt());
        }
    }

    /** @param month período avaliado P ({@code YYYY-MM}); nulo nos insights sem período (reserva) */
    public record InsightPeriodDto(String month, boolean partial) {}

    /** @param available falso nos placeholders "Em breve" (objetivo, Copilot) */
    public record InsightActionDto(String kind, String label, boolean primary, boolean available,
                                   Map<String, String> params) {

        static InsightActionDto of(InsightAction action) {
            return new InsightActionDto(action.kind().name(), action.label(), action.primary(), action.available(),
                    action.params());
        }
    }

    /**
     * Um insight na lista, nos destaques e nos relacionados.
     *
     * @param typeLabel    "Gastos", "Receitas", "Saúde" ou "Cartões"
     * @param subjectLabel o assunto na meta do card; sem ele, o rótulo do tipo
     * @param status       {@code ACTIVE}, {@code RESOLVED}, {@code EXPIRED} ou {@code DISMISSED}
     * @param seen         o usuário já abriu o detalhe ("Novo" = ativo e não visto)
     * @param confidence   {@code HIGH}, {@code MEDIUM} ou {@code LOW}
     * @param feedback     {@code USEFUL}, {@code NOT_USEFUL} ou nulo
     */
    public record InsightItemDto(UUID id, String detector, String type, String typeLabel, String importance,
                                 String status, String title, String support, String subjectLabel,
                                 InsightPeriodDto period, Instant createdAt, Instant updatedAt, boolean seen,
                                 String confidence, String feedback, InsightActionDto primaryAction) {

        public static InsightItemDto of(Insight insight) {
            InsightContent content = insight.content();
            String typeLabel = InsightResponses.typeLabel(insight.type());
            return new InsightItemDto(insight.id(), insight.detector().name(), insight.type().name(), typeLabel,
                    insight.importance().name(), insight.status().name(), content.title(), content.support(),
                    content.subjectLabel() == null ? typeLabel : content.subjectLabel(),
                    new InsightPeriodDto(insight.detector().periodBound() ? insight.periodKey() : null,
                            insight.partial()),
                    insight.createdAt(), insight.updatedAt(), insight.seenAt() != null, insight.confidence().name(),
                    insight.feedback() == null ? null : insight.feedback().name(),
                    InsightActionDto.of(content.primaryAction()));
        }
    }

    public record CardsCountDto(long count, boolean available) {}

    /** Contagens dos chips, sempre sobre os ativos e sem filtro; Cartões disponível desde a insights-v2. */
    public record InsightCountsDto(long all, long important, long spending, long income, long health,
                                   CardsCountDto cards) {

        static InsightCountsDto of(InsightStore.ChipCounts counts) {
            return new InsightCountsDto(counts.all(), counts.important(), counts.spending(), counts.income(),
                    counts.health(), new CardsCountDto(counts.cards(), true));
        }
    }

    /** @param state {@code NO_DATA}, {@code INSUFFICIENT_HISTORY} ou {@code READY} */
    public record InsightListResponse(UUID workspaceId, String algorithmVersion, String state,
                                      InsightsFreshnessDto freshness, InsightPeriodDto period,
                                      InsightCountsDto counts, List<InsightItemDto> items, int page, int pageSize,
                                      long totalItems) {

        public static InsightListResponse from(UUID workspaceId, InsightViews.Page page) {
            return new InsightListResponse(workspaceId, InsightRules.ALGORITHM_VERSION, page.state().name(),
                    InsightsFreshnessDto.of(page.freshness()), InsightResponses.period(page.period()),
                    InsightCountsDto.of(page.counts()), page.items().stream().map(InsightItemDto::of).toList(),
                    page.page(), page.pageSize(), page.totalItems());
        }
    }

    public record InsightRecommendationDto(String title, String text) {}

    /** @param value decimal na unidade de {@code kind} ({@code MONEY}, {@code PERCENT}, {@code COUNT}, ...) */
    public record InsightFigureDto(String code, String label, String kind, String value, LocalDate from,
                                   LocalDate to) {}

    public record InsightPointDto(String month, String value, boolean partial) {}

    /** @param kind unidade dos pontos: {@code MONEY}, {@code POINTS} (score) ou {@code MONTHS} (cobertura) */
    public record InsightSeriesDto(String kind, List<InsightPointDto> points) {}

    /**
     * @param key    chave do comerciante, código da categoria ou id da transação; nula = não identificado
     * @param amount valor no período (em D2, a alta da categoria)
     * @param share  % do total do insight, 1 casa; nulo quando não se aplica
     */
    public record InsightContributorDto(String kind, String key, String name, MoneyDto amount, Integer count,
                                        String share, LocalDate day) {}

    public record InsightLimitationDto(String code, String text) {}

    public record InsightSourceDto(String source, LocalDate from, LocalDate to) {}

    /** @param score nota da dimensão no snapshot do período; nula sem nota */
    public record InsightDimensionDto(String code, String label, Integer score) {}

    /**
     * Detalhe (ADR-0023 §1): o item, mais resumo, evidências, explicação, limitações, fontes, ações e relacionados.
     *
     * @param dataSources {@code CSV}, {@code MANUAL}, {@code OFX} (ordem alfabética): origens dos lançamentos no período
     *                    das fontes do insight
     * @param related     até 3 insights ativos com o mesmo assunto no mesmo período
     * @param dimension   dimensão do Health afetada; nula se nenhuma
     */
    public record InsightDetailResponse(UUID workspaceId, String algorithmVersion, InsightsFreshnessDto freshness,
                                        InsightItemDto insight, InsightRecommendationDto recommendation,
                                        List<InsightFigureDto> figures, InsightSeriesDto series,
                                        List<InsightContributorDto> contributors, String comparison,
                                        List<InsightLimitationDto> limitations, List<InsightSourceDto> sources,
                                        List<String> dataSources, List<InsightActionDto> actions,
                                        List<InsightItemDto> related, InsightDimensionDto dimension) {

        public static InsightDetailResponse from(UUID workspaceId, InsightViews.Detail detail) {
            Insight insight = detail.insight();
            InsightContent content = insight.content();
            InsightEvidence evidence = content.evidence();
            String currency = insight.impactAmount() == null ? "BRL"
                    : insight.impactAmount().currency().getCurrencyCode();
            return new InsightDetailResponse(workspaceId, insight.algorithmVersion(),
                    InsightsFreshnessDto.of(detail.freshness()), InsightItemDto.of(insight),
                    content.recommendation() == null ? null : new InsightRecommendationDto(
                            content.recommendation().title(), content.recommendation().text()),
                    evidence.figures().stream().map(f -> new InsightFigureDto(f.code(), f.label(), f.kind().name(),
                            decimal(f.kind(), f.value(), currency), f.from(), f.to())).toList(),
                    new InsightSeriesDto(seriesKind(insight.detector()).name(), evidence.series().stream()
                            .map(p -> new InsightPointDto(p.month().toString(), decimal(seriesKind(insight.detector()), p.value(), currency),
                                    p.partial())).toList()),
                    evidence.contributors().stream().map(c -> contributor(c, currency)).toList(),
                    evidence.comparison(),
                    evidence.limitations().stream().map(l -> new InsightLimitationDto(l.code().name(), l.text()))
                            .toList(),
                    evidence.sources().stream().map(s -> new InsightSourceDto(s.source().code(), s.from(), s.to()))
                            .toList(),
                    detail.dataSources().stream().map(Enum::name).sorted().toList(),
                    content.actions().stream().map(InsightActionDto::of).toList(),
                    detail.related().stream().map(InsightItemDto::of).toList(),
                    detail.dimension() == null ? null : new InsightDimensionDto(detail.dimension().dimension().name(),
                            detail.dimension().dimension().label(), detail.dimension().score()));
        }
    }

    /** Uma linha do "O que mudou?", já formatada pelo detector. */
    public record InsightChangeDto(UUID insightId, String detector, String type, String label, String value,
                                   String detail) {}

    /**
     * Destaques da Home (ADR-0023 §5).
     *
     * @param attention      "Merece atenção": até 2 Importantes
     * @param changes        "O que mudou?": até 3 linhas de insights de mudança
     * @param importantCount Importantes ativos (badge do M-More)
     */
    public record InsightHighlightsResponse(UUID workspaceId, String algorithmVersion, String state,
                                            InsightsFreshnessDto freshness, InsightPeriodDto period,
                                            List<InsightItemDto> attention, List<InsightChangeDto> changes,
                                            long importantCount) {

        public static InsightHighlightsResponse from(UUID workspaceId, InsightViews.Highlights highlights) {
            return new InsightHighlightsResponse(workspaceId, InsightRules.ALGORITHM_VERSION,
                    highlights.state().name(), InsightsFreshnessDto.of(highlights.freshness()),
                    InsightResponses.period(highlights.period()),
                    highlights.attention().stream().map(InsightItemDto::of).toList(),
                    highlights.changes().stream().map(c -> new InsightChangeDto(c.insightId(),
                            c.insight().detector().name(), c.insight().type().name(), c.line().label(),
                            c.line().value(), c.line().detail())).toList(),
                    highlights.importantCount());
        }
    }

    public record InsightRelatedResponse(UUID workspaceId, String algorithmVersion, InsightsFreshnessDto freshness,
                                         List<InsightItemDto> items) {

        public static InsightRelatedResponse from(UUID workspaceId, InsightViews.Related related) {
            return new InsightRelatedResponse(workspaceId, InsightRules.ALGORITHM_VERSION,
                    InsightsFreshnessDto.of(related.freshness()),
                    related.items().stream().map(InsightItemDto::of).toList());
        }
    }

    private static InsightPeriodDto period(InsightViews.Period period) {
        return new InsightPeriodDto(period.month().toString(), period.partial());
    }

    private static InsightContributorDto contributor(Contributor c, String currency) {
        return new InsightContributorDto(c.kind().name(), c.key(), c.name(), new MoneyDto(money(c.amount(), currency), currency),
                c.count(), c.share() == null ? null : c.share().toPlainString(), c.day());
    }

    /** Dinheiro como no Health (casas da moeda, sem zeros extras); as demais unidades como gravadas. */
    private static String decimal(FigureKind kind, BigDecimal value, String currency) {
        return kind == FigureKind.MONEY ? money(value, currency) : value.toPlainString();
    }

    private static String money(BigDecimal value, String currency) {
        return new Money(value, Currency.getInstance(currency)).toPlainString();
    }

    /** Unidade da série de cada detector. */
    static FigureKind seriesKind(InsightDetector detector) {
        return switch (detector) {
            case HEALTH_SCORE_CHANGE, HEALTH_DIMENSION_CHANGE -> FigureKind.POINTS;
            case CARD_UTILIZATION_HIGH -> FigureKind.PERCENT;
            case LOW_RESERVE -> FigureKind.MONTHS;
            default -> FigureKind.MONEY;
        };
    }

    static String typeLabel(InsightType type) {
        return switch (type) {
            case SPENDING -> "Gastos";
            case INCOME -> "Receitas";
            case HEALTH -> "Saúde";
            case CARDS -> "Cartões";
        };
    }
}
