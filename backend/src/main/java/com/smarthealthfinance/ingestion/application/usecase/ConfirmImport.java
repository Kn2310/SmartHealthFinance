package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Confirmação do preview (ADR-0009 §2/§13/§15): PREVIEW → CONFIRMED e evento {@code import.confirmed} na outbox,
 * na mesma transação. O processamento é assíncrono. Repetir a confirmação não emite outro evento.
 */
@Service
public class ConfirmImport {

    public static final String EVENT_TYPE = "import.confirmed";

    private static final Logger log = LoggerFactory.getLogger(ConfirmImport.class);

    private final ImportAccess access;
    private final ImportBatchRepository batches;
    private final EventOutbox outbox;
    private final Clock clock;

    public ConfirmImport(ImportAccess access, ImportBatchRepository batches, EventOutbox outbox, Clock clock) {
        this.access = access;
        this.batches = batches;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @throws com.smarthealthfinance.ingestion.domain.exception.InvalidImportStatusTransitionException cancelado,
     *         expirado ou preview vencido
     * @throws com.smarthealthfinance.accounts.domain.exception.AccountArchivedException conta arquivada
     */
    @Transactional
    public ImportBatchView execute(UUID workspaceId, UUID importId) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        // Lock: duas confirmações simultâneas emitem um único evento.
        ImportBatch batch = access.requireBatchForUpdate(workspace, importId);

        if (!batch.status().wasConfirmed()) {
            access.requireActiveAccount(workspace, batch.accountId());
        }
        if (batch.confirm(clock.instant())) {
            batches.save(batch);
            outbox.append(new IntegrationEvent(EVENT_TYPE, 1, workspace.value(), "ImportBatch", batch.id().value(),
                    null, Map.of("importId", batch.id().value().toString(),
                            "accountId", batch.accountId().value().toString())));
            log.info("Import confirmed importId={} workspaceId={} validLines={}", batch.id(), workspace,
                    batch.validLines());
        }
        return ImportBatchView.from(batch, batches.existsCompletedWithFile(workspace, batch.accountId(),
                batch.fileSha256(), batch.id()));
    }
}
