package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.model.RecurringSeries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mescla a detecção com a série persistida (ADR-0030 §2, ADR-0031 §4). Função pura: nunca altera o estado do
 * usuário.
 * <ul>
 *   <li>chave já existente: atualiza só os campos detectados e as ocorrências ({@code RecurringSeries.withDetection});
 *   {@code status}, {@code origin}, {@code user*} e {@code statusChangedAt} ficam como estão (inclusive em
 *   {@code IGNORED}: o mesmo {@code direction + key} não volta a ser sugerido);</li>
 *   <li>chave nova: entra como {@code DETECTED}/{@code DETECTED};</li>
 *   <li>chave que deixou de ser candidata: continua na lista como está (nunca é removida).</li>
 * </ul>
 */
public final class RecurrenceMerge {

    private RecurrenceMerge() {
    }

    public static List<RecurringSeries> merge(List<RecurringSeries> existing, List<RecurringSeries> detected) {
        Map<RecurrenceDetector.SeriesRef, RecurringSeries> merged = new LinkedHashMap<>();
        existing.forEach(series -> merged.put(ref(series), series));
        for (RecurringSeries series : detected) {
            merged.merge(ref(series), series, RecurringSeries::withDetection);
        }
        List<RecurringSeries> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(RecurrenceMerge::ref));
        return result;
    }

    static RecurrenceDetector.SeriesRef ref(RecurringSeries series) {
        return new RecurrenceDetector.SeriesRef(series.direction(), series.key());
    }
}
