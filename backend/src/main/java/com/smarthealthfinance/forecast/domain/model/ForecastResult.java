package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.Limitation;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Resultado do {@code forecast-v1} de um Workspace (ADR-0030, ADR-0031 §4): todas as projeções, sempre com a versão.
 * <p>
 * Sem histórico suficiente ({@code INSUFFICIENT_HISTORY}) só há fatos: {@code nextIncome}, {@code cashFlow} e
 * {@code savingCapacity} ficam nulos, os eventos têm só fatos e as projeções de mês e de fatura trazem só a parte de
 * fato.
 *
 * @param historyMonths meses fechados desde o primeiro lançamento
 * @param limitations   limitações globais de histórico
 * @param series        todas as séries depois da redetecção e da mescla (para gravar), com a situação delas hoje
 * @param month         sempre presente; sem histórico suficiente traz só os fatos
 */
public record ForecastResult(String algorithmVersion, LocalDate asOf, int historyMonths,
                             List<Limitation> limitations, List<SeriesAssessment> series, MonthProjection month,
                             NextIncome nextIncome, List<UpcomingEvent> upcomingEvents,
                             List<CardInvoiceProjection> invoices, CashFlowProjection cashFlow,
                             SavingCapacity savingCapacity) {

    public ForecastResult {
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        Objects.requireNonNull(asOf, "asOf");
        limitations = List.copyOf(limitations);
        series = List.copyOf(series);
        upcomingEvents = List.copyOf(upcomingEvents);
        invoices = List.copyOf(invoices);
    }
}
