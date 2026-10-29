package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * O que todas as projeções compartilham: a entrada, o tamanho do histórico, os meses de base do gasto variável e as
 * séries avaliadas hoje.
 *
 * @param historyMonths meses fechados desde o primeiro lançamento
 * @param baseMonths    até {@value ConfidenceRules#MAX_BASE_MONTHS} meses fechados mais recentes; vazio sem histórico
 *                      suficiente (nesse caso não há estimativa, só fatos)
 * @param uncategorized mais de 15% da despesa da base sem categoria
 */
record ProjectionContext(ForecastInput input, int historyMonths, List<YearMonth> baseMonths,
                         List<SeriesAssessment> assessments, boolean uncategorized) {

    static ProjectionContext of(ForecastInput input, List<SeriesAssessment> assessments) {
        int history = ConfidenceRules.historyMonths(input.firstActivity(), input.today());
        List<YearMonth> base = new ArrayList<>();
        if (ConfidenceRules.sufficient(history)) {
            YearMonth current = input.month();
            int size = Math.min(ConfidenceRules.MAX_BASE_MONTHS, history);
            for (int back = size; back >= 1; back--) {
                base.add(current.minusMonths(back));
            }
        }
        return new ProjectionContext(input, history, base, assessments,
                ConfidenceRules.uncategorizedExceeded(input.entries(), base));
    }

    boolean sufficient() {
        return !baseMonths.isEmpty();
    }

    LocalDate today() {
        return input.today();
    }

    YearMonth month() {
        return input.month();
    }

    Money zero() {
        return input.zero();
    }

    List<SeriesAssessment> eligible(SeriesDirection direction) {
        return assessments.stream().filter(a -> a.isEligible() && a.series().direction() == direction).toList();
    }

    /** Chaves das séries elegíveis do sentido: o que sai do "variável" porque já é projetado como recorrente. */
    Set<String> eligibleKeys(SeriesDirection direction) {
        return eligible(direction).stream().map(a -> a.series().key()).collect(Collectors.toSet());
    }
}
