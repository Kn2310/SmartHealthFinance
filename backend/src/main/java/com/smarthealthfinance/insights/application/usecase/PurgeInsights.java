package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.insights.application.port.InsightStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retenção (ADR-0022 §6): apaga insights não visíveis ({@code DISMISSED}, {@code RESOLVED}, {@code EXPIRED}) com
 * {@code updated_at} há mais de {@value #RETENTION_MONTHS} meses.
 */
@Service
public class PurgeInsights {

    public static final int RETENTION_MONTHS = 13;

    private static final Logger log = LoggerFactory.getLogger(PurgeInsights.class);

    private final InsightStore store;
    private final Clock clock;

    public PurgeInsights(InsightStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** @return quantos insights foram apagados */
    @Transactional
    public int execute() {
        Instant before = clock.instant().atOffset(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS).toInstant();
        int purged = store.purgeHiddenBefore(before);
        log.info("Insights purged count={}", purged);
        return purged;
    }
}
