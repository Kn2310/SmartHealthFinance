package com.smarthealthfinance.copilot.infrastructure.scheduling;

import com.smarthealthfinance.copilot.application.usecase.PurgeExpiredCopilotData;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retenção diária do histórico e do feedback do Copilot (ADR-0040 §6, §8): repete em lotes até esgotar o que expirou.
 * O horário vem de {@code shf.copilot.history.retention.cron} (padrão: 03:30).
 */
@Component
public class CopilotRetentionJob {

    private final PurgeExpiredCopilotData purge;

    public CopilotRetentionJob(PurgeExpiredCopilotData purge) {
        this.purge = purge;
    }

    @Scheduled(cron = "${shf.copilot.history.retention.cron:0 30 3 * * *}")
    public void purgeExpired() {
        while (purge.execute().full(PurgeExpiredCopilotData.BATCH_LIMIT)) {
            // continua até sobrar menos que um lote cheio
        }
    }
}
