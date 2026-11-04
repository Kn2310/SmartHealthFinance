package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;

import java.util.List;
import java.util.Objects;

/**
 * Cálculo do Forecast {@value #ALGORITHM_VERSION} (ADR-0030): puro e determinístico, sem I/O e sem relógio do
 * sistema ("hoje" vem na entrada). A entrada já traz o que as portas de Analytics, Overview, Cards e contas
 * fornecem (ADR-0031 §1).
 * <p>
 * Ordem: redetecção das séries → mescla com a série persistida (nunca altera o estado do usuário) → situação das
 * séries hoje → projeção do mês → faturas → próxima receita → próximos eventos → fluxo de caixa → capacidade de
 * aporte. Mudou fórmula, limiar ou entrada → nova versão (uma {@code forecast-v2} grava ao lado).
 */
public final class ForecastCalculator {

    public static final String ALGORITHM_VERSION = RecurrenceDetector.ALGORITHM_VERSION;

    private ForecastCalculator() {
    }

    public static ForecastResult calculate(ForecastInput input) {
        Objects.requireNonNull(input, "input");

        List<RecurringSeries> detected = RecurrenceDetector.detect(input.entries(), input.today());
        List<RecurringSeries> merged = RecurrenceMerge.merge(input.existingSeries(), detected);
        List<SeriesAssessment> assessments = SeriesAssessor.assess(merged, detected, input.entries(), input.today());
        ProjectionContext ctx = ProjectionContext.of(input, assessments);

        var month = MonthProjector.project(ctx);
        var invoices = CardInvoiceProjector.project(ctx, CashFlowProjector.horizonEnd(input.today()));
        List<CardInvoiceProjection> invoiceProjections = invoices.projections();

        return new ForecastResult(ALGORITHM_VERSION, input.today(), ctx.historyMonths(),
                ConfidenceRules.historyLimitations(ctx.historyMonths()), assessments, month,
                NextIncomeProjector.project(ctx).orElse(null),
                UpcomingEventsProjector.project(ctx, invoiceProjections), invoiceProjections,
                CashFlowProjector.project(ctx, invoices).orElse(null),
                SavingCapacityProjector.project(ctx).orElse(null));
    }
}
