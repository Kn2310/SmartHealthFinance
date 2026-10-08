package com.smarthealthfinance.ingestion.infrastructure.scheduling;

import com.smarthealthfinance.ingestion.application.usecase.ExpireImportPreviews;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expira previews não confirmados (ADR-0009 §16), em rodadas limitadas até esgotar os vencidos. */
@Component
public class ImportPreviewExpiryJob {

    private final ExpireImportPreviews expirePreviews;

    public ImportPreviewExpiryJob(ExpireImportPreviews expirePreviews) {
        this.expirePreviews = expirePreviews;
    }

    @Scheduled(cron = "${shf.ingestion.preview-expiry.cron:0 */10 * * * *}")
    public void expire() {
        while (expirePreviews.execute() == ExpireImportPreviews.BATCH_LIMIT) {
            // continua até sobrar menos que uma rodada cheia
        }
    }
}
