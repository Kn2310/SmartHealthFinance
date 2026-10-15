package com.smarthealthfinance.health.application.service;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recalcula o snapshot inteiro de um Workspace (ADR-0018 §4): calcula cada mês com o {@link HealthCalculator},
 * encadeia a variação contra o mês anterior e grava o mês corrente + os 12 fechados na versão atual. Chamado com o
 * Workspace já bloqueado (job ou rebuild), dentro da transação de quem chama.
 */
@Service
public class HealthSnapshotCalculator {

    private final HealthInputLoader loader;
    private final HealthSnapshotStore store;
    private final Clock clock;
    private final MeterRegistry meters;

    public HealthSnapshotCalculator(HealthInputLoader loader, HealthSnapshotStore store, Clock clock,
                                    MeterRegistry meters) {
        this.loader = loader;
        this.store = store;
        this.clock = clock;
        this.meters = meters;
    }

    /** @return os resultados gravados, do mais antigo ao corrente */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<HealthResult> recalculate(WorkspaceId workspaceId) {
        Timer.Sample sample = Timer.start(meters);
        List<HealthResult> results = calculate(loader.load(workspaceId));
        store.replace(workspaceId, HealthCalculator.ALGORITHM_VERSION, results, clock.instant());
        sample.stop(Timer.builder("shf.health.snapshot.compute").tag("result", results.isEmpty() ? "empty" : "ok")
                .register(meters));
        return results;
    }

    /**
     * @param inputs do mais antigo ao corrente; o primeiro só serve de base para a variação do segundo quando a
     *               lista tem o mês extra (ver {@link HealthInputLoader#load})
     */
    public static List<HealthResult> calculate(List<HealthInput> inputs) {
        List<HealthResult> all = new ArrayList<>();
        HealthResult previous = null;
        for (HealthInput input : inputs) {
            HealthResult result = HealthCalculator.withVariation(HealthCalculator.calculate(input), previous);
            all.add(result);
            previous = result;
        }
        int keep = HealthInputLoader.STORED_CLOSED_MONTHS + 1;
        return all.size() > keep ? List.copyOf(all.subList(all.size() - keep, all.size())) : List.copyOf(all);
    }
}
