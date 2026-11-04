package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.shared.domain.Money;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Um valor exposto pelo Forecast (ADR-0030 §1, §4, §5, §6): basis, as partes de fato e de estimativa, a faixa
 * [low, high], a confiança, as limitações e as evidências. {@code value = factPart + estimatedPart}.
 * <p>
 * Um total que junta fato e estimativa é sempre {@code ESTIMATED} e traz as duas partes. Um fato soma o mesmo valor
 * nos dois limites; estimativas somam mínimos e máximos.
 *
 * @param partial valor parcial: só em {@code FACT} (ex.: fatura aberta); continua fato
 */
public record Projected(Basis basis, Money value, Money factPart, Money estimatedPart, Money low, Money high,
                        boolean partial, Confidence confidence, List<Limitation> limitations,
                        List<Evidence> evidence) {

    public Projected {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(factPart, "factPart");
        Objects.requireNonNull(estimatedPart, "estimatedPart");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(confidence, "confidence");
        limitations = List.copyOf(limitations);
        evidence = List.copyOf(evidence);
        if (basis == Basis.SIMULATED) {
            throw new IllegalArgumentException("forecast-v1 nunca produz SIMULATED");
        }
        if (value.amount().compareTo(factPart.plus(estimatedPart).amount()) != 0) {
            throw new IllegalArgumentException("value deve ser factPart + estimatedPart");
        }
        if (low.amount().compareTo(value.amount()) > 0 || value.amount().compareTo(high.amount()) > 0) {
            throw new IllegalArgumentException("A faixa deve conter o valor central");
        }
        if (basis == Basis.FACT && (estimatedPart.amount().signum() != 0
                || low.amount().compareTo(value.amount()) != 0 || high.amount().compareTo(value.amount()) != 0)) {
            throw new IllegalArgumentException("FACT não tem parte estimada nem faixa");
        }
        if (basis != Basis.FACT && partial) {
            throw new IllegalArgumentException("Só um fato é parcial");
        }
    }

    /** Um fato (possivelmente parcial): faixa degenerada, confiança alta. */
    public static Projected fact(Money value, boolean partial, List<Limitation> limitations, List<Evidence> evidence) {
        return new Projected(Basis.FACT, value, value, Money.zero(value.currency()), value, value, partial,
                Confidence.HIGH, limitations, evidence);
    }

    public static Projected fact(Money value) {
        return fact(value, false, List.of(), List.of());
    }

    /**
     * Fato + estimativa. {@code estimatedRange} é a faixa da parte estimada (já propagada); o limite de baixo e o de
     * cima do total somam o fato aos dois.
     */
    public static Projected estimated(Money fact, Money estimated, Range estimatedRange, Confidence confidence,
                                      List<Limitation> limitations, List<Evidence> evidence) {
        return new Projected(Basis.ESTIMATED, fact.plus(estimated), fact, estimated,
                fact.plus(estimatedRange.min()), fact.plus(estimatedRange.max()), false, confidence, limitations,
                evidence);
    }

    public Projected plus(Projected other) {
        return combine(other, factPart.plus(other.factPart), estimatedPart.plus(other.estimatedPart),
                low.plus(other.low), high.plus(other.high));
    }

    /** {@code this − other}: o limite de baixo desconta o de cima de {@code other}, e vice-versa (conservador). */
    public Projected minus(Projected other) {
        return combine(other, factPart.minus(other.factPart), estimatedPart.minus(other.estimatedPart),
                low.minus(other.high), high.minus(other.low));
    }

    private Projected combine(Projected other, Money fact, Money estimated, Money newLow, Money newHigh) {
        Basis combined = basis == Basis.ESTIMATED || other.basis == Basis.ESTIMATED ? Basis.ESTIMATED : Basis.FACT;
        LinkedHashSet<Limitation> merged = new LinkedHashSet<>(limitations);
        merged.addAll(other.limitations);
        List<Evidence> evidences = new ArrayList<>(evidence);
        evidences.addAll(other.evidence);
        Confidence lowest = confidence.min(other.confidence);
        return new Projected(combined, fact.plus(estimated), fact, estimated, newLow, newHigh,
                combined == Basis.FACT && (partial || other.partial), lowest, List.copyOf(merged), evidences);
    }

    /** O mesmo valor com a confiança e as limitações reavaliadas (regras do §5 aplicadas pelo chamador). */
    public Projected assessed(Confidence newConfidence, List<Limitation> newLimitations) {
        return new Projected(basis, value, factPart, estimatedPart, low, high, partial, newConfidence,
                newLimitations, evidence);
    }

    public boolean isEstimated() {
        return basis == Basis.ESTIMATED;
    }
}
