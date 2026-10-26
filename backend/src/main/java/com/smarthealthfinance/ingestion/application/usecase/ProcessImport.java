package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.cards.application.exception.CardNotFoundException;
import com.smarthealthfinance.cards.application.usecase.RecordImportedCardTransactions;
import com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.port.ImportedTransactionKeys;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.model.ImportRecord;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement.StatementClosing;
import com.smarthealthfinance.ingestion.domain.model.StatementLine;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import com.smarthealthfinance.ingestion.domain.service.CardStatementRules;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
import com.smarthealthfinance.ingestion.domain.valueobject.InstallmentMark;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Processa um batch confirmado (ADR-0009 §12/§15), chamado pelo Import Worker para cada {@code import.confirmed}.
 * <p>
 * Duas transações de banco:
 * <ol>
 *     <li>CONFIRMED → PROCESSING, para a UI acompanhar;</li>
 *     <li>tudo-ou-nada: reserva das chaves de dedupe, criação das transações (por Transactions, ou por Cards numa
 *     fatura de cartão, §20), resultado de cada linha, COMPLETED, inbox e {@code import.completed} na outbox.</li>
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
    private final RecordImportedCardTransactions recordCardTransactions;
    private final ProcessedEvents processedEvents;
    private final EventOutbox outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProcessImport(ImportAccess access, ImportBatchRepository batches, ImportRecordRepository records,
                         ImportedTransactionKeys keys, RecordImportedTransactions recordTransactions,
                         RecordImportedCardTransactions recordCardTransactions, ProcessedEvents processedEvents,
                         EventOutbox outbox, TransactionTemplate transactions, Clock clock) {
        this.access = access;
        this.batches = batches;
        this.records = records;
        this.keys = keys;
        this.recordTransactions = recordTransactions;
        this.recordCardTransactions = recordCardTransactions;
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

    /** O que a gravação das linhas válidas produziu. */
    private record Recorded(List<ImportRecord> results, int imported, int reconciled, int categorized,
                            Optional<LocalDate> earliestCreated) {
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
        ImportDestination destination = batch.destination();

        String unavailable = destinationUnavailability(workspace, destination);
        if (unavailable != null) {
            // Não retentável: a conta ou o cartão deixou de aceitar movimento depois do preview.
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

        Set<DedupeKey> claimed = keys.claim(workspace, destination, importId, claims);
        List<ImportRecord> won = pending.stream().filter(record -> claimed.contains(record.dedupeKey())).toList();
        List<DedupeKey> lost = pending.stream().map(ImportRecord::dedupeKey).filter(key -> !claimed.contains(key))
                .toList();
        Map<DedupeKey, TransactionId> existing = lost.isEmpty() ? Map.of()
                : keys.findExisting(workspace, destination, lost);

        Recorded recorded = destination.isCard()
                ? recordOnCard(workspace, batch, won, newIds)
                : recordOnAccount(workspace, batch, won, newIds);

        List<ImportRecord> results = new ArrayList<>(recorded.results());
        pending.stream().filter(record -> !claimed.contains(record.dedupeKey()))
                .forEach(record -> results.add(record.duplicateOf(existing.get(record.dedupeKey()))));
        records.saveResults(workspace, results);

        batch.complete(recorded.imported(), recorded.reconciled(), lost.size(), recorded.categorized(), now);
        batches.save(batch);
        outbox.append(new IntegrationEvent(COMPLETED_EVENT_TYPE, 1, workspace.value(), "ImportBatch",
                importId.value(), eventId, completedPayload(importId, batch, recorded.earliestCreated())));

        log.info("Import completed importId={} workspaceId={} imported={} reconciled={} duplicates={} invalid={}",
                importId, workspace, batch.importedLines(), batch.reconciledLines(), batch.duplicateLines(),
                batch.invalidLines());
    }

    private Recorded recordOnAccount(WorkspaceId workspace, ImportBatch batch, List<ImportRecord> won,
                                     Map<DedupeKey, TransactionId> newIds) {
        List<RecordImportedTransactions.Line> lines = new ArrayList<>();
        List<ImportRecord> results = new ArrayList<>();
        for (ImportRecord record : won) {
            TransactionId id = newIds.get(record.dedupeKey());
            lines.add(new RecordImportedTransactions.Line(id, record.line().isInflow(),
                    record.line().absoluteAmount(), record.line().occurredOn(), record.line().description()));
            results.add(record.imported(id));
        }
        RecordImportedTransactions.Result recorded = recordTransactions.execute(workspace,
                batch.destination().requireAccount(), lines);
        return new Recorded(results, lines.size(), 0, recorded.categorized(), Optional.empty());
    }

    /**
     * Fatura de cartão (ADR-0009 §20–§28): Cards lança compras e créditos, reconstrói ou concilia as parcelas e recua o
     * início do acompanhamento. A chave de uma parcela conciliada passa a apontar para a compra existente (§27).
     */
    private Recorded recordOnCard(WorkspaceId workspace, ImportBatch batch, List<ImportRecord> won,
                                  Map<DedupeKey, TransactionId> newIds) {
        if (won.isEmpty()) {
            return new Recorded(List.of(), 0, 0, 0, Optional.empty());
        }
        List<RecordImportedCardTransactions.Line> lines = new ArrayList<>(won.size());
        for (ImportRecord record : won) {
            StatementLine line = record.line();
            InstallmentMark installment = record.installment();
            lines.add(new RecordImportedCardTransactions.Line(newIds.get(record.dedupeKey()).value(),
                    line.isInflow(), line.absoluteAmount(), line.occurredOn(),
                    installment == null ? line.description() : CardStatementRules.purchaseDescription(line),
                    installment == null ? null : installment.number(),
                    installment == null ? null : installment.count()));
        }

        Optional<StatementClosing> closing = batch.closing();
        // Toda linha com data entra na fatura do arquivo, inclusive pagamentos e duplicadas (§25).
        LocalDate fileLastDate = batch.lastDate().orElseThrow();
        RecordImportedCardTransactions.Result recorded = recordCardTransactions.execute(workspace,
                batch.destination().id(), new RecordImportedCardTransactions.Statement(fileLastDate,
                        closing.map(StatementClosing::dueDate).orElse(null),
                        closing.map(StatementClosing::minimumPayment).orElse(null)), lines);

        List<ImportRecord> results = new ArrayList<>(won.size());
        Map<DedupeKey, TransactionId> reconciledKeys = new HashMap<>();
        int imported = 0;
        for (int i = 0; i < won.size(); i++) {
            ImportRecord record = won.get(i);
            RecordImportedCardTransactions.LineResult result = recorded.lines().get(i);
            TransactionId transaction = new TransactionId(result.transactionId());
            if (result.outcome() == RecordImportedCardTransactions.Outcome.RECONCILED) {
                reconciledKeys.put(record.dedupeKey(), transaction);
                results.add(record.reconciledWith(transaction));
            }
            else {
                imported++;
                results.add(record.imported(transaction));
            }
        }
        keys.reassign(workspace, batch.destination(), reconciledKeys);
        return new Recorded(results, imported, reconciledKeys.size(), recorded.categorized(),
                recorded.earliestDate());
    }

    /**
     * Contagens e o período afetado ({@code firstDate}/{@code lastDate}, só quando há linhas com data): o Analytics
     * recalcula os meses desse período (ADR-0014 §2). Uma compra reconstruída pode ser anterior ao arquivo (§29).
     * Nunca valores ou descrições.
     */
    private static Map<String, Object> completedPayload(ImportBatchId importId, ImportBatch batch,
                                                        Optional<LocalDate> earliestCreated) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("importId", importId.value().toString());
        payload.put(batch.destination().isCard() ? "cardId" : "accountId", batch.destination().id().toString());
        payload.put("importedLines", batch.importedLines());
        payload.put("categorizedLines", batch.categorizedLines());
        payload.put("duplicateLines", batch.duplicateLines());
        payload.put("invalidLines", batch.invalidLines());
        if (batch.destination().isCard()) {
            payload.put("reconciledLines", batch.reconciledLines());
            payload.put("skippedLines", batch.skippedLines());
        }
        Optional<LocalDate> first = batch.firstDate()
                .map(date -> earliestCreated.filter(created -> created.isBefore(date)).orElse(date));
        first.ifPresent(date -> payload.put("firstDate", date.toString()));
        batch.lastDate().ifPresent(date -> payload.put("lastDate", date.toString()));
        return payload;
    }

    /** @return o motivo da falha, ou nulo se a conta ou o cartão aceita movimento */
    private String destinationUnavailability(WorkspaceId workspace, ImportDestination destination) {
        try {
            access.requireAvailable(workspace, destination);
            return null;
        }
        catch (AccountArchivedException archived) {
            return "ACCOUNT_ARCHIVED";
        }
        catch (AccountNotFoundException missing) {
            return "ACCOUNT_NOT_FOUND";
        }
        catch (CreditCardArchivedException archived) {
            return "CARD_ARCHIVED";
        }
        catch (CardNotFoundException missing) {
            return "CARD_NOT_FOUND";
        }
    }
}
