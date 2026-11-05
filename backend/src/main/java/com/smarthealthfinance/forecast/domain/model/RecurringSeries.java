package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Periodicity;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Série recorrente de um Workspace (ADR-0030 §2). A identidade é {@code direction + key}.
 * <p>
 * Os campos <b>detectados</b> (canal, dia, valor, faixa, confiança, ocorrências, versão) são os únicos que a
 * redetecção escreve; o <b>estado do usuário</b> ({@code status}, {@code origin}, {@code userExpectedAmount},
 * {@code userExpectedDay}, {@code statusChangedAt}) nunca é alterado por ela ({@link #withDetection}).
 *
 * @param key                {@code merchant:<chave>}; receita sem comerciante usa {@code category:<código>}
 * @param label              nome de exibição (comerciante ou categoria); só texto de apresentação
 * @param expectedDay        dia mais frequente; em meses mais curtos vale o último dia do mês
 * @param userExpectedAmount valor editado pelo usuário; vale como exato (faixa = o valor, confiança HIGH)
 * @param statusChangedAt    quando o usuário mudou o estado; nulo se nunca
 */
public record RecurringSeries(SeriesDirection direction, String key, String label, Channel channel,
                              Periodicity periodicity, int expectedDay, Money expectedAmount, Range amountRange,
                              Confidence confidence, List<UUID> occurrences, String algorithmVersion,
                              SeriesStatus status, SeriesOrigin origin, Money userExpectedAmount,
                              Integer userExpectedDay, Instant statusChangedAt) {

    /** Tolerância de data da ocorrência no ciclo (ADR-0030 §2). */
    public static final int DATE_TOLERANCE_DAYS = 3;

    public RecurringSeries {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(periodicity, "periodicity");
        Objects.requireNonNull(expectedAmount, "expectedAmount");
        Objects.requireNonNull(amountRange, "amountRange");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(origin, "origin");
        occurrences = List.copyOf(occurrences);
        requireDay(expectedDay);
        if (userExpectedDay != null) {
            requireDay(userExpectedDay);
        }
        if (!expectedAmount.isPositive() || (userExpectedAmount != null && !userExpectedAmount.isPositive())) {
            throw new IllegalArgumentException("Valores esperados devem ser positivos");
        }
    }

    private static void requireDay(int day) {
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException("Dia fora de 1-31");
        }
    }

    public int effectiveDay() {
        return userExpectedDay != null ? userExpectedDay : expectedDay;
    }

    public Money effectiveAmount() {
        return userExpectedAmount != null ? userExpectedAmount : expectedAmount;
    }

    /** Valor editado vale como exato: a faixa é o próprio valor. */
    public Range effectiveRange() {
        return userExpectedAmount != null ? Range.point(userExpectedAmount) : amountRange;
    }

    /** Valor editado vale como exato: confiança HIGH. */
    public Confidence effectiveConfidence() {
        return userExpectedAmount != null ? Confidence.HIGH : confidence;
    }

    /** Data esperada no mês; em meses mais curtos, o último dia do mês. */
    public LocalDate expectedDateIn(YearMonth month) {
        return month.atDay(Math.min(effectiveDay(), month.lengthOfMonth()));
    }

    /** Confirma a série (ADR-0030 §2). Idempotente: confirmada de novo não muda nada. */
    public RecurringSeries confirm(Instant now) {
        return withStatus(SeriesStatus.CONFIRMED, now);
    }

    /** Ignora a série: o mesmo {@code direction + key} não volta a ser sugerido. Idempotente. */
    public RecurringSeries ignore(Instant now) {
        return withStatus(SeriesStatus.IGNORED, now);
    }

    /**
     * Edita valor e/ou dia esperados (campos {@code user*}); o estado e a elegibilidade não mudam. O valor editado
     * vale como exato.
     *
     * @param amount nulo = mantém o valor editado atual; moeda igual à do valor detectado
     * @param day    nulo = mantém o dia editado atual
     */
    public RecurringSeries edit(Money amount, Integer day) {
        if (amount == null && day == null) {
            throw new InvalidValueException("amount", "REQUIRED");
        }
        if (amount != null && !amount.isPositive()) {
            throw new InvalidValueException("amount", "MUST_BE_POSITIVE");
        }
        if (amount != null && !amount.currency().equals(expectedAmount.currency())) {
            throw new InvalidValueException("amount", "CURRENCY_MISMATCH");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new InvalidValueException("expectedDay", "OUT_OF_RANGE");
        }
        return new RecurringSeries(direction, key, label, channel, periodicity, expectedDay, expectedAmount,
                amountRange, confidence, occurrences, algorithmVersion, status, origin,
                amount != null ? amount : userExpectedAmount, day != null ? day : userExpectedDay, statusChangedAt);
    }

    /** Remove o ajuste do usuário (valor e dia): voltam os valores detectados. Estado e elegibilidade não mudam. */
    public RecurringSeries clearAdjustment() {
        if (userExpectedAmount == null && userExpectedDay == null) {
            return this;
        }
        return new RecurringSeries(direction, key, label, channel, periodicity, expectedDay, expectedAmount,
                amountRange, confidence, occurrences, algorithmVersion, status, origin, null, null, statusChangedAt);
    }

    private RecurringSeries withStatus(SeriesStatus newStatus, Instant now) {
        if (status == newStatus) {
            return this;
        }
        return new RecurringSeries(direction, key, label, channel, periodicity, expectedDay, expectedAmount,
                amountRange, confidence, occurrences, algorithmVersion, newStatus, origin, userExpectedAmount,
                userExpectedDay, now);
    }

    /** Atualiza só os campos detectados e as ocorrências; o estado do usuário fica como está. */
    public RecurringSeries withDetection(RecurringSeries detected) {
        return new RecurringSeries(direction, key, detected.label, detected.channel, periodicity,
                detected.expectedDay, detected.expectedAmount, detected.amountRange, detected.confidence,
                detected.occurrences, detected.algorithmVersion, status, origin, userExpectedAmount,
                userExpectedDay, statusChangedAt);
    }
}
