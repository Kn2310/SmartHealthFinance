package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Resultado do Financial Health de um mês (ADR-0017), sempre com a versão do algoritmo.
 *
 * @param asOf               data-base
 * @param windowFrom         primeiro dia da janela W; nulo se n = 0
 * @param windowTo           último dia da janela W; nulo se n = 0
 * @param monthsUsed         n: meses fechados na janela
 * @param score              0–100; nulo em {@code INSUFFICIENT_DATA}
 * @param variation          score − score do mês anterior; nulo sem os dois scores
 * @param uncategorizedShare % do gasto da janela sem categoria, 1 casa
 * @param summary            frase do resumo; nula sem score
 * @param activeCards        cartões ativos acompanhados na data-base (só contexto, {@code health-v2})
 */
public record HealthResult(String algorithmVersion, YearMonth month, LocalDate asOf, boolean partial,
                           LocalDate windowFrom, LocalDate windowTo, int monthsUsed, HealthState state,
                           Integer score, Integer variation, boolean estimated, Confidence confidence,
                           BigDecimal uncategorizedShare, Coverage coverage, int activeAccounts, String summary,
                           List<DimensionResult> dimensions, List<Factor> factors, List<NextStep> nextSteps,
                           int activeCards) {

    public HealthResult(String algorithmVersion, YearMonth month, LocalDate asOf, boolean partial,
                        LocalDate windowFrom, LocalDate windowTo, int monthsUsed, HealthState state,
                        Integer score, Integer variation, boolean estimated, Confidence confidence,
                        BigDecimal uncategorizedShare, Coverage coverage, int activeAccounts, String summary,
                        List<DimensionResult> dimensions, List<Factor> factors, List<NextStep> nextSteps) {
        this(algorithmVersion, month, asOf, partial, windowFrom, windowTo, monthsUsed, state, score, variation,
                estimated, confidence, uncategorizedShare, coverage, activeAccounts, summary, dimensions, factors,
                nextSteps, 0);
    }

    /** Dimensões com nota e a fração do peso total (0–100) que elas somam. */
    public record Coverage(int available, int total, int weightPercent) {}

    public HealthResult {
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(uncategorizedShare, "uncategorizedShare");
        Objects.requireNonNull(coverage, "coverage");
        dimensions = List.copyOf(dimensions);
        factors = List.copyOf(factors);
        nextSteps = List.copyOf(nextSteps);
        if ((state == HealthState.READY) != (score != null)) {
            throw new IllegalArgumentException("Só READY tem score");
        }
    }

    public HealthResult withVariation(Integer value) {
        return new HealthResult(algorithmVersion, month, asOf, partial, windowFrom, windowTo, monthsUsed, state,
                score, value, estimated, confidence, uncategorizedShare, coverage, activeAccounts, summary,
                dimensions, factors, nextSteps, activeCards);
    }
}
