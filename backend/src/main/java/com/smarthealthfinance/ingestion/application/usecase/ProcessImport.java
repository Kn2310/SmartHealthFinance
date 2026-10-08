package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.port.ImportedTransactionKeys;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.model.ImportRecord;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import com.smarthealthfinance.shared.application.port.ProcessedEvents;
import com.smarthealthfinance.transactions.application.usecase.RecordImportedTransactions;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Processa um batch confirmado (ADR-0009 §12/§15), chamado pelo Import Worker para cada {@code import.confirmed}.
 * <p>
 * Duas transações de banco:
 * <ol>
 *     <li>CONFIRMED → PROCESSING, para a UI acompanhar;</li>
 *     <li>tudo-ou-nada: reserva das chaves de dedupe, criação das transações (por Transactions), resultado de cada
 *     linha, COMPLETED, inbox e {@code import.completed} na outbox.</li>
 * </ol>
 * Idempotente: uma reentrega encontra o evento na inbox ou o batch fora de CONFIRMED/PROCESSING e não faz nada.
 * Uma falha na segunda transação deixa o batch em PROCESSING e a mensagem volta a ser entregue.
 */
@Service
public class ProcessImport {

    public static final String CONSUMER = "ingestion.import-processor";
    public static final String COMPLETED_EVENT_TYPE = "import.completed";

    private static final Logger log = LoggerFactory.getLogger(ProcessImport.class);

    private final ImportAccess access;
    private final ImportBatchRepository batches;
    private final ImportRecordRepository records;
    private final ImportedTransactionKeys keys;
    private final RecordImportedTransactions recordTransactions;
    private final ProcessedEvents processedEvents;
    private final EventOutbox outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProcessImport(ImportAccess access, ImportBatchRepository batches, ImportRecordRepository records,
                         ImportedTransactionKeys keys, RecordImportedTransactions recordTransactions,
                         ProcessedEvents processedEvents, EventOutbox outbox, TransactionTemplate transactions,
                         Clock clock) {
        this.access = access;
        this.batches = batches;
        this.records = records;
        this.keys = keys;
        this.recordTransactions = recordTransactions;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void execute(UUID eventId, WorkspaceId workspace, ImportBatchId importId) {
        Boolean started = transactions.execute(status -> start(workspace, importId));
        if (!Boolean.TRUE.equals(started)) {
            return;
        }
        transactions.executeWithoutResult(status -> process(eventId, workspace, importId));
    }

    private boolean start(WorkspaceId workspace, ImportBatchId importId) {
        ImportBatch batch = batches.findByIdForUpdate(workspace, importId).orElse(null);
        if (batch == null) {
            log.warn("Import to process not found importId={} workspaceId={}", importId, workspace);
            return false;
        }
        if (!batch.status().awaitsProcessing()) {
            log.info("Import already processed importId={} status={}", importId, batch.status());
            return false;
        }
        batch.startProcessing(clock.instant());
        batches.save(batch);
        return true;
    }

    private void process(UUID eventId, WorkspaceId workspace, ImportBatchId importId) {
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            log.info("Import event already processed importId={} eventId={}", importId, eventId);
            return;
        }
        ImportBatch batch = batches.findByIdForUpdate(workspace, importId).orElseThrow();
        if (!batch.status().awaitsProcessing()) {
            return;
        }
        Instant now = clock.instant();

        String unavailable = accountUnavailability(workspace, batch);
        if (unavailable != null) {
            // Não retentável: a conta deixou de aceitar movimento depois do preview.
            batch.fail(unavailable, now);
            batches.save(batch);
            log.warn("Import failed importId={} workspaceId={} reason={}", importId, workspace, unavailable);
            return;
        }

        List<ImportRecord> pending = records.findAll(workspace, importId, RecordStatus.VALID);
        List<ImportedTransactionKeys.Claim> claims = pending.stream()
                .map(record -> new ImportedTransactionKeys.Claim(record.dedupeKey(), TransactionId.generate(now)))
                .toList();
        Map<DedupeKey, TransactionId> newIds = claims.stream()
                .collect(Collectors.toMap(ImportedTransactionKeys.Claim::key,
                        ImportedTransactionKeys.Claim::transactionId));

        Set<DedupeKey> claimed = keys.claim(workspace, batch.accountId(), importId, claims);
        List<DedupeKey> lost = pending.stream().map(ImportRecord::dedupeKey).filter(key -> !claimed.contains(key))
                .toList();
        Map<DedupeKey, TransactionId> existing = lost.isEmpty() ? Map.of()
                : keys.findExisting(workspace, batch.accountId(), lost);

        List<RecordImportedTransactions.Line> lines = new ArrayList<>();
        List<ImportRecord> results = new ArrayList<>(pending.size());
        for (ImportRecord record : pending) {
            if (claimed.contains(record.dedupeKey())) {
                TransactionId id = newIds.get(record.dedupeKey());
                lines.add(new RecordImportedTransactions.Line(id, record.line().isInflow(),
                        record.line().absoluteAmount(), record.line().occurredOn(), record.line().description()));
                results.add(record.imported(id));
            }
            else {
                results.add(record.duplicateOf(existing.get(record.dedupeKey())));
            }
        }

        recordTransactions.execute(workspace, batch.accountId(), lines);
        records.saveResults(workspace, results);

        batch.complete(lines.size(), lost.size(), now);
        batches.save(batch);
        outbox.append(new IntegrationEvent(COMPLETED_EVENT_TYPE, 1, workspace.value(), "ImportBatch",
                importId.value(), eventId, Map.of(
                        "importId", importId.value().toString(),
                        "accountId", batch.accountId().value().toString(),
                        "importedLines", batch.importedLines(),
                        "duplicateLines", batch.duplicateLines(),
                        "invalidLines", batch.invalidLines())));

        log.info("Import completed importId={} workspaceId={} imported={} duplicates={} invalid={}", importId,
                workspace, batch.importedLines(), batch.duplicateLines(), batch.invalidLines());
    }

    /** @return o motivo da falha, ou nulo se a conta aceita movimento */
    private String accountUnavailability(WorkspaceId workspace, ImportBatch batch) {
        try {
            access.requireActiveAccount(workspace, batch.accountId());
            return null;
        }
        catch (AccountArchivedException archived) {
            return "ACCOUNT_ARCHIVED";
        }
        catch (AccountNotFoundException missing) {
            return "ACCOUNT_NOT_FOUND";
        }
    }
}
