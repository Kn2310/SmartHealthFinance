package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Projeção do mês corrente (ADR-0030 §3.1): {@code realizado (FACT) + recorrentes pendentes + variável restante}.
 *
 * @param baseMonths número de meses fechados usados no variável restante (0 = sem estimativa)
 * @param expense    despesa projetada do mês
 * @param income     receita projetada do mês (realizada + recorrentes pendentes)
 * @param net        {@code income − expense}, com as mesmas partes
 * @param pending    as ocorrências recorrentes ainda pendentes que entraram na conta
 */
public record MonthProjection(YearMonth month, LocalDate asOf, int baseMonths, Projected expense,
                              Projected income, Projected net, List<PendingOccurrence> pending) {

    public MonthProjection {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(expense, "expense");
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(net, "net");
        pending = List.copyOf(pending);
    }

    /** Uma ocorrência esperada de série elegível, ainda não realizada. */
    public record PendingOccurrence(SeriesDirection direction, String key, String label, LocalDate date,
                                    Money expected, Range range, Confidence confidence, Channel channel) {}
}
