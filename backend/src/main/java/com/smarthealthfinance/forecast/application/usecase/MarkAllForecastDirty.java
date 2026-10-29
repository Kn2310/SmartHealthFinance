package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mudança sem evento: o calendário (ADR-0031 §6). Todo dia o mês corrente avança e, na virada, o mês fecha e
 * as ocorrências, os pendentes e o STALE mudam; o job diário marca todos os Workspaces com snapshot para o recálculo normal com debounce.
 */
@Service
public class MarkAllForecastDirty {

    private static final Logger log = LoggerFactory.getLogger(MarkAllForecastDirty.class);

    private final ForecastSnapshotStore store;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;

    public MarkAllForecastDirty(ForecastSnapshotStore store, Clock clock,
                              @Value("${shf.forecast.debounce.quiet:10s}") Duration quiet,
                              @Value("${shf.forecast.debounce.max-wait:60s}") Duration maxWait) {
        this.store = store;
        this.clock = clock;
        this.quiet = quiet;
        this.maxWait = maxWait;
    }

    @Transactional
    public int execute() {
        int marked = store.markAllWithSnapshotsDirty(clock.instant(), quiet, maxWait);
        log.info("Forecast daily refresh marked workspaces={}", marked);
        return marked;
    }
}
