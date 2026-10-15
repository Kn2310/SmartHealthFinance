package com.smarthealthfinance.health.application.dto;

import com.smarthealthfinance.health.domain.model.HealthFreshness;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Leituras do Health (ADR-0019): sempre o snapshot gravado, mais a freshness. */
public final class HealthViews {

    private HealthViews() {
    }

    /**
     * @param result  nulo = sem snapshot para o mês ({@code NO_DATA})
     * @param sources origens dos lançamentos da janela, lidas na hora (não fazem parte do score)
     */
    public record Summary(YearMonth month, HealthResult result, HealthFreshness freshness, Set<DataSource> sources,
                          Currency currency) {

        public Summary {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(freshness, "freshness");
            sources = Set.copyOf(sources);
        }
    }

    /** @param result nulo = mês sem snapshot */
    public record Month(YearMonth month, HealthResult result) {}

    /** @param months do mais antigo ao mais recente, sempre {@code months} itens */
    public record Evolution(YearMonth to, List<Month> months, HealthFreshness freshness) {

        public Evolution {
            months = List.copyOf(months);
        }
    }
}
