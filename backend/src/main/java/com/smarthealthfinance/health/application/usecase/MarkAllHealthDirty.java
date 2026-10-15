package com.smarthealthfinance.health.application.usecase;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mudança sem evento: o calendário (ADR-0018 §5). Todo dia o mês corrente avança e, na virada, o mês fecha e a
 * janela W muda; o job diário marca todos os Workspaces com snapshot para o recálculo normal com debounce.
 */
@Service
public class MarkAllHealthDirty {

    private static final Logger log = LoggerFactory.getLogger(MarkAllHealthDirty.class);

    private final HealthSnapshotStore store;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;

    public MarkAllHealthDirty(HealthSnapshotStore store, Clock clock,
                              @Value("${shf.health.debounce.quiet:10s}") Duration quiet,
                              @Value("${shf.health.debounce.max-wait:60s}") Duration maxWait) {
        this.store = store;
        this.clock = clock;
        this.quiet = quiet;
        this.maxWait = maxWait;
    }

    @Transactional
    public int execute() {
        int marked = store.markAllWithSnapshotsDirty(clock.instant(), quiet, maxWait);
        log.info("Health daily refresh marked workspaces={}", marked);
        return marked;
    }
}
