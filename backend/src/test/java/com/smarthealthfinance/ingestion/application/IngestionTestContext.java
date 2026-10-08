package com.smarthealthfinance.ingestion.application;

import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.application.service.StatementFiles;
import com.smarthealthfinance.ingestion.application.usecase.CancelImport;
import com.smarthealthfinance.ingestion.application.usecase.ConfirmImport;
import com.smarthealthfinance.ingestion.application.usecase.ExpireImportPreviews;
import com.smarthealthfinance.ingestion.application.usecase.FailImport;
import com.smarthealthfinance.ingestion.application.usecase.ListImportRecords;
import com.smarthealthfinance.ingestion.application.usecase.ListImports;
import com.smarthealthfinance.ingestion.application.usecase.ProcessImport;
import com.smarthealthfinance.ingestion.application.usecase.UploadStatement;
import com.smarthealthfinance.ingestion.infrastructure.parser.CsvStatementParser;
import com.smarthealthfinance.ingestion.infrastructure.parser.OfxStatementParser;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import com.smarthealthfinance.shared.application.port.ProcessedEvents;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.usecase.RecordImportedTransactions;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Financial Core do {@link TransactionsTestContext} (Ana com Aurora/Norte/arquivada, Bob) + Ingestion em memória.
 * O relógio do Ingestion avança com {@link #advance}.
 */
public final class IngestionTestContext {

    public final TransactionsTestContext core = new TransactionsTestContext();

    public final InMemoryImportBatchRepository batches = new InMemoryImportBatchRepository();
    public final InMemoryImportRecordRepository records = new InMemoryImportRecordRepository();
    public final InMemoryImportedTransactionKeys keys = new InMemoryImportedTransactionKeys();
    public final List<IntegrationEvent> events = new ArrayList<>();
    public final Set<String> processedEvents = new HashSet<>();
    public final MutableClock clock = new MutableClock(TransactionsTestContext.NOW);

    public final EventOutbox outbox = event -> {
        events.add(event);
        return UUID.randomUUID();
    };
    public final ProcessedEvents inbox = (consumer, eventId) -> processedEvents.add(consumer + "/" + eventId);
    public final TransactionTemplate transactions = new TransactionTemplate(new NoOpTransactionManager());

    public final ImportAccess access = new ImportAccess(core.currentUserService,
            new WorkspaceAccessGuard(core.workspaces), batches, core.accounts);
    public final StatementFiles files = new StatementFiles(List.of(new CsvStatementParser(),
            new OfxStatementParser()));

    public final UploadStatement upload = new UploadStatement(access, files, batches, records, keys, clock);
    public final ConfirmImport confirm = new ConfirmImport(access, batches, outbox, clock);
    public final CancelImport cancel = new CancelImport(access, batches, records, clock);
    public final ListImports list = new ListImports(access, batches);
    public final ListImportRecords listRecords = new ListImportRecords(access, records);
    public final ProcessImport process = new ProcessImport(access, batches, records, keys,
            new RecordImportedTransactions(core.access, core.transactions, clock), inbox, outbox, transactions,
            clock);
    public final FailImport fail = new FailImport(batches, clock);
    public final ExpireImportPreviews expire = new ExpireImportPreviews(batches, records, clock);

    public UUID workspaceId() {
        return core.anasWorkspaceId();
    }

    public UploadStatement.Command csv(String key, UUID accountId, String content) {
        return new UploadStatement.Command(key, accountId, "extrato.csv", content.getBytes(StandardCharsets.UTF_8));
    }

    public void advance(Duration duration) {
        clock.instant = clock.instant.plus(duration);
    }

    public static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** O TransactionTemplate só delimita fases nos testes de aplicação; não há banco. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
