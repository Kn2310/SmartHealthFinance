package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Periodicity;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Detecção determinística de recorrências (ADR-0030 §2), sobre os {@value #HISTORY_MONTHS} meses fechados anteriores
 * ao mês corrente.
 * <ul>
 *   <li>candidata: a chave tem <b>exatamente uma</b> ocorrência em pelo menos {@value #MIN_MONTHS} desses meses e
 *   cada ocorrência fica a até ±10% da mediana (mesma regra do D3, {@code InsightRules.RECURRING_TOLERANCE_PERCENT});
 *   </li>
 *   <li>piso de R$ 10 na mediana ({@code InsightRules.RECURRING_MIN_AMOUNT});</li>
 *   <li>despesa sem comerciante não vira recorrência; parcelas de cartão nunca viram recorrência.</li>
 * </ul>
 * A saída são séries {@code DETECTED}/{@code DETECTED} sem campos do usuário; a mescla com a série persistida é de
 * {@link RecurrenceMerge}. Os limiares do D3 são copiados por valor para não depender do módulo insights.
 */
public final class RecurrenceDetector {

    public static final String ALGORITHM_VERSION = "forecast-v1";
    public static final int HISTORY_MONTHS = 6;
    public static final int MIN_MONTHS = 3;
    /** Igual a {@code InsightRules.RECURRING_TOLERANCE_PERCENT} (ADR-0030 §2). */
    static final BigDecimal TOLERANCE_PERCENT = new BigDecimal("10");
    /** Igual a {@code InsightRules.RECURRING_MIN_AMOUNT} (ADR-0030 §2). */
    static final BigDecimal MIN_AMOUNT = new BigDecimal("10");
    /** HIGH: amplitude de até 5% da mediana. */
    static final BigDecimal HIGH_AMPLITUDE_PERCENT = new BigDecimal("5");
    static final int HIGH_MIN_MONTHS = 5;
    static final int MEDIUM_MIN_MONTHS = 4;

    private RecurrenceDetector() {
    }

    /** Os 6 meses fechados anteriores ao de {@code today}, do mais antigo ao mais recente. */
    public static List<YearMonth> windowMonths(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        List<YearMonth> months = new ArrayList<>(HISTORY_MONTHS);
        for (int back = HISTORY_MONTHS; back >= 1; back--) {
            months.add(current.minusMonths(back));
        }
        return months;
    }

    public static List<RecurringSeries> detect(List<ForecastEntry> entries, LocalDate today) {
        List<YearMonth> window = windowMonths(today);
        Map<SeriesRef, Map<YearMonth, List<ForecastEntry>>> groups = new TreeMap<>();
        for (ForecastEntry entry : entries) {
            SeriesDirection direction = SeriesKeys.directionOf(entry);
            String key = SeriesKeys.keyOf(entry);
            YearMonth month = YearMonth.from(entry.day());
            if (direction == null || key == null || entry.installment() || !window.contains(month)) {
                continue;
            }
            groups.computeIfAbsent(new SeriesRef(direction, key), k -> new TreeMap<>())
                    .computeIfAbsent(month, k -> new ArrayList<>()).add(entry);
        }

        List<RecurringSeries> detected = new ArrayList<>();
        groups.forEach((ref, months) -> candidate(ref, months).ifPresent(detected::add));
        return detected;
    }

    private static Optional<RecurringSeries> candidate(SeriesRef ref,
                                                                 Map<YearMonth, List<ForecastEntry>> months) {
        List<ForecastEntry> occurrences = months.values().stream()
                .filter(list -> list.size() == 1)
                .map(List::getFirst)
                .sorted(Comparator.comparing(ForecastEntry::day))
                .toList();
        if (occurrences.size() < MIN_MONTHS) {
            return Optional.empty();
        }
        List<Money> amounts = occurrences.stream().map(ForecastEntry::amount).toList();
        Money median = Statistics.median(amounts);
        if (median.amount().compareTo(MIN_AMOUNT) < 0 || !allWithinTolerance(amounts, median)) {
            return Optional.empty();
        }
        Range range = Statistics.rangeOf(amounts);
        return Optional.of(new RecurringSeries(ref.direction(), ref.key(), label(occurrences),
                channel(occurrences), Periodicity.MONTHLY, typicalDay(occurrences), median, range,
                confidence(occurrences.size(), median, range), occurrences.stream()
                        .map(ForecastEntry::transactionId).toList(),
                ALGORITHM_VERSION, SeriesStatus.DETECTED, SeriesOrigin.DETECTED, null, null, null));
    }

    /** Cada ocorrência a até ±10% da mediana. */
    static boolean allWithinTolerance(List<Money> amounts, Money median) {
        BigDecimal allowed = median.amount().multiply(TOLERANCE_PERCENT);
        return amounts.stream().allMatch(a ->
                a.minus(median).amount().abs().multiply(BigDecimal.valueOf(100)).compareTo(allowed) <= 0);
    }

    /** HIGH: 5 ou 6 meses e amplitude até 5%; MEDIUM: 4 ou mais meses, ou amplitude até 10%; senão LOW. */
    static Confidence confidence(int months, Money median, Range range) {
        BigDecimal amplitudeTimes100 = range.amplitude().amount().multiply(BigDecimal.valueOf(100));
        boolean upTo5 = amplitudeTimes100.compareTo(median.amount().multiply(HIGH_AMPLITUDE_PERCENT)) <= 0;
        boolean upTo10 = amplitudeTimes100.compareTo(median.amount().multiply(TOLERANCE_PERCENT)) <= 0;
        if (months >= HIGH_MIN_MONTHS && upTo5) {
            return Confidence.HIGH;
        }
        if (months >= MEDIUM_MIN_MONTHS || upTo10) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    /** O dia mais frequente (regra do {@code typicalDay}); no empate, o menor. */
    static int typicalDay(List<ForecastEntry> occurrences) {
        Map<Integer, Integer> frequency = new TreeMap<>();
        occurrences.forEach(e -> frequency.merge(e.day().getDayOfMonth(), 1, Integer::sum));
        return frequency.entrySet().stream()
                .reduce((best, next) -> next.getValue() > best.getValue() ? next : best)
                .orElseThrow().getKey();
    }

    /** A conta ou o cartão mais frequente; no empate, conta antes de cartão e depois pelo id. */
    static Channel channel(List<ForecastEntry> occurrences) {
        Map<Channel, Integer> frequency = new HashMap<>();
        occurrences.forEach(e -> frequency.merge(e.channel(), 1, Integer::sum));
        return frequency.entrySet().stream()
                .sorted(Map.Entry.<Channel, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Channel.ORDER)))
                .findFirst().orElseThrow().getKey();
    }

    /** Nome de exibição: o menor, em ordem, entre as ocorrências (como em {@code FlowLedger.merchantNames}). */
    static String label(List<ForecastEntry> occurrences) {
        return occurrences.stream()
                .map(e -> e.merchantName() != null ? e.merchantName() : e.categoryCode())
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Identidade de uma série. */
    record SeriesRef(SeriesDirection direction, String key) implements Comparable<SeriesRef> {

        @Override
        public int compareTo(SeriesRef other) {
            int byDirection = direction.compareTo(other.direction);
            return byDirection != 0 ? byDirection : key.compareTo(other.key);
        }
    }
}
