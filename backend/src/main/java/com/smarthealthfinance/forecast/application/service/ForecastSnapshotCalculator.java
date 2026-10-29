package com.smarthealthfinance.forecast.application.service;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore;
import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore.Stored;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.OutcomePhase;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.forecast.domain.service.MonthOutcomes;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Recalcula o snapshot de um Workspace (ADR-0031 §3, §6): carrega a entrada pelas portas, roda o
 * {@link ForecastCalculator}, grava as séries (só as colunas detectadas), congela e reconcilia o previsto × realizado,
 * grava o snapshot e o {@code forecast.snapshot-updated}. Chamado com o Workspace já bloqueado (job ou rebuild), dentro
 * da transação de quem chama; o lock também serializa as ações do usuário em recorrências.
 * <p>
 * Previsto × realizado:
 * <ul>
 *   <li>se o snapshot anterior é de um mês anterior ao corrente, a projeção daquele mês é congelada
 *   ({@code ON CONFLICT DO NOTHING}), exceto sem histórico suficiente (só fatos, nada a comparar);</li>
 *   <li>cada mês ainda não liquidado, enquanto a data de negócio for até o dia 5 do mês seguinte, tem só o realizado e
 *   o erro regravados;</li>
 *   <li>o primeiro recálculo depois do dia 5 regrava o realizado uma última vez e liquida o mês
 *   ({@code settled_at}); a partir daí a linha é imutável. Um mês mais antigo que a janela lida das portas só é
 *   liquidado, sem realizado.</li>
 * </ul>
 */
@Service
public class ForecastSnapshotCalculator {

    private final ForecastInputLoader loader;
    private final ForecastSnapshotStore store;
    private final RecurringSeriesRepository series;
    private final MonthOutcomeStore outcomes;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ForecastSnapshotEvents events;

    public ForecastSnapshotCalculator(ForecastInputLoader loader, ForecastSnapshotStore store,
                                      RecurringSeriesRepository series, MonthOutcomeStore outcomes, Clock clock,
                                      MeterRegistry meters, ForecastSnapshotEvents events) {
        this.loader = loader;
        this.store = store;
        this.series = series;
        this.outcomes = outcomes;
        this.clock = clock;
        this.meters = meters;
        this.events = events;
    }

    /** @return o resultado gravado */
    @Transactional(propagation = Propagation.MANDATORY)
    public ForecastResult recalculate(WorkspaceId workspaceId) {
        Timer.Sample sample = Timer.start(meters);
        Instant now = clock.instant();

        Optional<ForecastResult> previous = store.find(workspaceId, ForecastCalculator.ALGORITHM_VERSION);
        ForecastInput input = loader.load(workspaceId);
        ForecastResult result = ForecastCalculator.calculate(input);

        series.upsertDetected(workspaceId, result.series().stream().map(SeriesAssessment::series).toList(), now);
        closeMonths(workspaceId, previous.orElse(null), input, now);
        store.replace(workspaceId, result, now);
        events.updated(workspaceId, ForecastCalculator.ALGORITHM_VERSION);

        sample.stop(Timer.builder("shf.forecast.snapshot.compute").tag("result", resultTag(result))
                .register(meters));
        return result;
    }

    private void closeMonths(WorkspaceId workspaceId, ForecastResult previous, ForecastInput input, Instant now) {
        String version = ForecastCalculator.ALGORITHM_VERSION;
        if (previous != null && !previous.limitations().contains(Limitation.INSUFFICIENT_HISTORY)) {
            MonthOutcomes.monthToFreeze(previous.asOf(), input.today())
                    .ifPresent(month -> outcomes.freeze(workspaceId, MonthOutcomes.freeze(previous.month(), version),
                            now));
        }

        YearMonth oldestRead = input.month().minusMonths(ForecastInputLoader.LOOKBACK_MONTHS);
        for (Stored stored : outcomes.findUnsettled(workspaceId, version)) {
            YearMonth month = stored.outcome().month();
            OutcomePhase phase = MonthOutcomes.phase(month, input.today());
            if (phase == OutcomePhase.CURRENT) {
                continue;
            }
            boolean readable = !month.isBefore(oldestRead);
            if (readable) {
                MonthOutcome reconciled = MonthOutcomes.reconcile(stored.outcome(),
                        MonthOutcomes.realized(input.entries(), month, input.zero()));
                outcomes.reconcile(workspaceId, reconciled);
                if (phase == OutcomePhase.SETTLED) {
                    recordError(reconciled);
                }
            }
            if (phase == OutcomePhase.SETTLED) {
                outcomes.settle(workspaceId, month, version, now);
            }
        }
    }

    /** Erro percentual do mês liquidado (ADR-0030 §6): só números agregados, sem valores nem textos. */
    private void recordError(MonthOutcome settled) {
        record("expense", settled.error().expensePercent());
        record("income", settled.error().incomePercent());
    }

    private void record(String kind, BigDecimal percent) {
        if (percent != null) {
            DistributionSummary.builder("shf.forecast.month.error").tag("kind", kind).register(meters)
                    .record(percent.doubleValue());
        }
    }

    private static String resultTag(ForecastResult result) {
        return result.limitations().contains(Limitation.INSUFFICIENT_HISTORY) ? "insufficient" : "ok";
    }
}
