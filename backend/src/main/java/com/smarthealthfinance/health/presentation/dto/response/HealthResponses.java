package com.smarthealthfinance.health.presentation.dto.response;

import com.smarthealthfinance.health.application.dto.HealthViews;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthFreshness;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.model.NextStep;
import com.smarthealthfinance.health.domain.service.HealthMethod;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Respostas do Health (ADR-0019), nas convenções da Análise (ADR-0015): dinheiro em {@link MoneyDto}, percentuais e
 * meses de cobertura como string decimal, enums como string. Os textos já vêm prontos (templates com os números
 * calculados, ADR-0017 §7); os campos estruturados existem para a UI montar links e gráficos.
 */
public final class HealthResponses {

    private HealthResponses() {
    }

    /** {@code READY}, {@code INSUFFICIENT_DATA} (sem score, só as dimensões) ou {@code NO_DATA} (sem snapshot). */
    static String stateOf(HealthResult result) {
        return result == null ? "NO_DATA" : result.state().name();
    }

    /** @param state {@code UP_TO_DATE}, {@code RECALCULATING} ou {@code NOT_BUILT} */
    public record HealthFreshnessDto(String state, Instant computedAt, Instant analyticsRefreshedAt) {

        static HealthFreshnessDto of(HealthFreshness freshness) {
            return new HealthFreshnessDto(freshness.state().name(), freshness.computedAt(),
                    freshness.analyticsRefreshedAt());
        }
    }

    /**
     * @param asOf       data-base (hoje no mês corrente; o último dia nos fechados); nula em {@code NO_DATA}
     * @param windowFrom primeiro dia da janela de meses fechados; nulo sem mês fechado
     * @param monthsUsed meses fechados na janela
     */
    public record HealthPeriodDto(String month, LocalDate asOf, boolean partial, LocalDate windowFrom,
                                  LocalDate windowTo, int monthsUsed) {}

    public record HealthCoverageDto(int available, int total, int weightPercent) {}

    /**
     * @param uncategorizedShare % do gasto da janela sem categoria
     * @param sources            {@code CSV}, {@code MANUAL}, {@code OFX} (ordem alfabética): origens dos lançamentos da
     *                           janela
     */
    public record HealthContextDto(int activeAccounts, String uncategorizedShare, List<String> sources) {}

    /**
     * @param kind  {@code MONEY} (na moeda da resposta), {@code PERCENT}, {@code MONTHS} ou {@code COUNT}
     * @param value decimal em string
     * @param source porta que produziu o número ({@code analytics.monthly}, {@code overview.account-balances}…)
     */
    public record HealthEvidenceDto(String code, String label, String kind, String value, LocalDate from,
                                    LocalDate to, String source) {

        static HealthEvidenceDto of(Evidence e) {
            return new HealthEvidenceDto(e.code(), e.label(), e.kind().name(), e.value().toPlainString(), e.from(),
                    e.to(), e.source().code());
        }
    }

    /**
     * @param status  {@code OK}, {@code INSUFFICIENT_DATA} ou {@code NOT_APPLICABLE}
     * @param score   0–100; nulo sem {@code OK}
     * @param reason  motivo sem nota; nulo com {@code OK}
     */
    public record HealthDimensionDto(String dimension, String label, int weight, String status, Integer score,
                                     String reason, String explanation, boolean attention,
                                     List<HealthEvidenceDto> evidence) {

        static HealthDimensionDto of(DimensionResult d) {
            return new HealthDimensionDto(d.dimension().name(), d.dimension().label(), d.dimension().weight(),
                    d.status().name(), d.score(), d.reason() == null ? null : d.reason().name(), d.explanation(),
                    d.attention(), d.evidence().stream().map(HealthEvidenceDto::of).toList());
        }
    }

    /** @param direction {@code SUSTAINING} ou {@code WEIGHING} */
    public record HealthFactorDto(String dimension, String direction, String text) {}

    /**
     * @param kind         {@code BUILD_RESERVE} (Contas), {@code ADD_RESERVE_ACCOUNT} (Adicionar conta),
     *                     {@code REVIEW_CATEGORY} (Análise › Categorias), {@code CATEGORIZE_TRANSACTIONS}
     *                     (Transações sem categoria) ou {@code REVIEW_MONTH} (Análise do mês)
     * @param categoryCode só {@code REVIEW_CATEGORY} (nulo = sem categoria)
     * @param month        só {@code REVIEW_CATEGORY} e {@code REVIEW_MONTH}
     * @param amount       só {@code BUILD_RESERVE}: quanto falta para 6 meses
     */
    public record HealthNextStepDto(String kind, String text, String evidence, String categoryCode, String month,
                                    MoneyDto amount) {

        static HealthNextStepDto of(NextStep s) {
            return new HealthNextStepDto(s.kind().name(), s.text(), s.evidence(), s.categoryCode(),
                    s.month() == null ? null : s.month().toString(), money(s.amount()));
        }
    }

    /**
     * Resumo, dimensões, fatores e próximos passos do mês. Em {@code NO_DATA}, score e blocos nulos/vazios.
     *
     * @param variation  score − score do mês anterior; nulo sem os dois
     * @param estimated  menos de 3 meses fechados: score estimado
     * @param confidence {@code HIGH}, {@code MEDIUM} ou {@code LOW}; nula em {@code NO_DATA}
     * @param summary    frase do resumo; nula sem score
     */
    public record HealthResponse(UUID workspaceId, String state, String algorithmVersion, String currency,
                                 HealthPeriodDto period, Integer score, Integer variation, boolean estimated,
                                 String confidence, HealthCoverageDto coverage, String summary,
                                 HealthContextDto context, HealthFreshnessDto freshness,
                                 List<HealthDimensionDto> dimensions, List<HealthFactorDto> factors,
                                 List<HealthNextStepDto> nextSteps) {

        public static HealthResponse from(UUID workspaceId, String algorithmVersion, HealthViews.Summary view) {
            HealthResult r = view.result();
            HealthFreshnessDto freshness = HealthFreshnessDto.of(view.freshness());
            if (r == null) {
                return new HealthResponse(workspaceId, stateOf(null), algorithmVersion,
                        view.currency().getCurrencyCode(),
                        new HealthPeriodDto(view.month().toString(), null, false, null, null, 0), null, null, false,
                        null, null, null, null, freshness, List.of(), List.of(), List.of());
            }
            return new HealthResponse(workspaceId, stateOf(r), r.algorithmVersion(), view.currency().getCurrencyCode(),
                    new HealthPeriodDto(r.month().toString(), r.asOf(), r.partial(), r.windowFrom(), r.windowTo(),
                            r.monthsUsed()),
                    r.score(), r.variation(), r.estimated(), r.confidence().name(),
                    new HealthCoverageDto(r.coverage().available(), r.coverage().total(),
                            r.coverage().weightPercent()),
                    r.summary(),
                    new HealthContextDto(r.activeAccounts(), r.uncategorizedShare().toPlainString(),
                            view.sources().stream().map(DataSource::name).sorted().toList()),
                    freshness, r.dimensions().stream().map(HealthDimensionDto::of).toList(),
                    r.factors().stream().map(f -> new HealthFactorDto(f.dimension().name(), f.direction().name(),
                            f.text())).toList(),
                    r.nextSteps().stream().map(HealthNextStepDto::of).toList());
        }
    }

    /** @param state {@code READY}, {@code INSUFFICIENT_DATA} ou {@code NO_DATA}; score nulo fora de READY */
    public record HealthMonthDto(String month, String state, Integer score, Integer variation, boolean estimated,
                                 boolean partial) {

        static HealthMonthDto of(HealthViews.Month m) {
            HealthResult r = m.result();
            return r == null ? new HealthMonthDto(m.month().toString(), stateOf(null), null, null, false, false)
                    : new HealthMonthDto(m.month().toString(), stateOf(r), r.score(), r.variation(), r.estimated(),
                    r.partial());
        }
    }

    /** Série mensal do score até {@code to}, sempre com {@code months} itens (os sem snapshot em NO_DATA). */
    public record HealthEvolutionResponse(UUID workspaceId, String algorithmVersion, String to,
                                          HealthFreshnessDto freshness, List<HealthMonthDto> months) {

        public static HealthEvolutionResponse from(UUID workspaceId, String algorithmVersion,
                                                   HealthViews.Evolution view) {
            return new HealthEvolutionResponse(workspaceId, algorithmVersion, view.to().toString(),
                    HealthFreshnessDto.of(view.freshness()), view.months().stream().map(HealthMonthDto::of).toList());
        }
    }

    public record HealthCurvePointDto(String x, String score) {}

    public record HealthCurveDto(String name, String input, List<HealthCurvePointDto> points) {}

    public record HealthMethodDimensionDto(String dimension, String label, int weight, boolean applicable,
                                           String observes, String formula, String minimum,
                                           List<HealthCurveDto> curves) {}

    public record HealthConfidenceRuleDto(String level, String rule) {}

    /** Conteúdo do "Como calculamos?" (D-HealthMethod), das mesmas constantes do cálculo. */
    public record HealthMethodResponse(String algorithmVersion, String about,
                                       List<HealthMethodDimensionDto> dimensions, String overall, String window,
                                       List<String> sources, String estimated,
                                       List<HealthConfidenceRuleDto> confidence, String partialMonth,
                                       String rounding, List<String> exclusions) {

        public static HealthMethodResponse from(HealthMethod.Description d) {
            return new HealthMethodResponse(d.algorithmVersion(), d.about(), d.dimensions().stream()
                    .map(x -> new HealthMethodDimensionDto(x.dimension().name(), x.label(), x.weight(),
                            x.applicable(), x.observes(), x.formula(), x.minimum(), x.curves().stream()
                            .map(c -> new HealthCurveDto(c.name(), c.input(), c.points().stream()
                                    .map(p -> new HealthCurvePointDto(plain(p.x()), plain(p.score()))).toList()))
                            .toList()))
                    .toList(), d.overall(), d.window(), d.sources(), d.estimated(),
                    d.confidence().stream().map(c -> new HealthConfidenceRuleDto(c.level().name(), c.rule())).toList(),
                    d.partialMonth(), d.rounding(), d.exclusions());
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static MoneyDto money(Money value) {
        return value == null ? null : new MoneyDto(value.toPlainString(), value.currency().getCurrencyCode());
    }
}
