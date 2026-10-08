package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.ingestion.application.IngestionTestContext;
import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;
import com.smarthealthfinance.ingestion.application.dto.ImportRecordView;
import com.smarthealthfinance.ingestion.application.exception.ImportNotFoundException;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.exception.InvalidImportStatusTransitionException;
import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.transactions.application.exception.IdempotencyKeyReusedException;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportFlowTest {

    private static final String SEPTEMBER = """
            data;descricao;valor;id
            01/09/2026;Salário;4.500,00;s1
            02/09/2026;Bistrô Lume;-86,40;s2
            03/09/2026;Valor ruim;abc;s3
            03/09/2026;Café;-5,00;s4
            """;

    /** Sobreposto ao anterior (s2, s4) e com dois lançamentos novos. */
    private static final String SEPTEMBER_OVERLAP = """
            data;descricao;valor;id
            02/09/2026;Bistrô Lume;-86,40;s2
            03/09/2026;Café;-5,00;s4
            04/09/2026;Mercado Sol;-210,35;s5
            05/09/2026;Pix recebido;150,00;s6
            """;

    private final IngestionTestContext ctx = new IngestionTestContext();

    @Nested
    class Upload {

        @Test
        void createsPreviewWithoutTransactions() {
            UploadStatement.Result result = upload("k-1", SEPTEMBER);

            ImportBatchView batch = result.batch();
            assertThat(result.replayed()).isFalse();
            assertThat(batch.status()).isEqualTo(ImportStatus.PREVIEW);
            assertThat(batch.format()).isEqualTo(ImportFormat.CSV);
            assertThat(batch.totalLines()).isEqualTo(4);
            assertThat(batch.validLines()).isEqualTo(3);
            assertThat(batch.invalidLines()).isEqualTo(1);
            assertThat(batch.duplicateLines()).isZero();
            assertThat(batch.sameFileImportedBefore()).isFalse();
            assertThat(batch.previewExpiresAt()).isEqualTo(ctx.clock.instant().plus(Duration.ofHours(24)));
            assertThat(ctx.core.transactions.size()).isZero();
            assertThat(ctx.events).isEmpty();
        }

        @Test
        void listsRecordsInFileOrderWithIssuesOnly() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();

            List<ImportRecordView> records = ctx.listRecords.execute(ctx.workspaceId(), batch.id(), null, null, null)
                    .items();

            assertThat(records).extracting(ImportRecordView::lineNumber).containsExactly(2, 3, 4, 5);
            assertThat(records.get(1).amount()).isEqualByComparingTo("86.40");
            assertThat(records.get(1).inflow()).isFalse();
            assertThat(records.get(2).status()).isEqualTo(RecordStatus.INVALID);
            assertThat(records.get(2).issueCode()).isEqualTo("INVALID_AMOUNT");
            assertThat(records.get(2).description()).isNull();
            assertThat(ctx.listRecords.execute(ctx.workspaceId(), batch.id(), "INVALID", null, null).totalItems())
                    .isEqualTo(1);
        }

        @Test
        void sameKeyAndFileIsReplayed() {
            ImportBatchView first = upload("k-1", SEPTEMBER).batch();

            UploadStatement.Result again = upload("k-1", SEPTEMBER);

            assertThat(again.replayed()).isTrue();
            assertThat(again.batch().id()).isEqualTo(first.id());
            assertThat(ctx.batches.size()).isEqualTo(1);
        }

        @Test
        void sameKeyWithAnotherFileIsRejected() {
            upload("k-1", SEPTEMBER);

            assertThatThrownBy(() -> upload("k-1", SEPTEMBER_OVERLAP))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
        }

        @Test
        void requiresIdempotencyKeyAndAccount() {
            assertInvalidValue(() -> upload(null, SEPTEMBER), "Idempotency-Key", "REQUIRED");
            assertInvalidValue(() -> ctx.upload.execute(ctx.workspaceId(), ctx.csv("k-1", null, SEPTEMBER)),
                    "accountId", "REQUIRED");
        }

        @Test
        void rejectedFileCreatesNothing() {
            assertThatThrownBy(() -> ctx.upload.execute(ctx.workspaceId(), new UploadStatement.Command("k-1",
                    id(ctx.core.aurora), "extrato.xlsx", new byte[] { 1, 2, 3 })))
                    .isInstanceOfSatisfying(StatementRejectedException.class,
                            ex -> assertThat(ex.reason()).isEqualTo("UNSUPPORTED_FILE_TYPE"));
            assertThatThrownBy(() -> upload("k-2", "data;descricao\n01/09/2026;X\n"))
                    .isInstanceOfSatisfying(StatementRejectedException.class,
                            ex -> assertThat(ex.reason()).isEqualTo("MISSING_COLUMN"));
            assertThat(ctx.batches.size()).isZero();
        }

        @Test
        void ofxRenamedToCsvIsRejected() {
            assertThatThrownBy(() -> upload("k-1", "OFXHEADER:100\n<OFX></OFX>"))
                    .isInstanceOfSatisfying(StatementRejectedException.class,
                            ex -> assertThat(ex.reason()).isEqualTo("FORMAT_MISMATCH"));
        }

        @Test
        void archivedOrForeignAccountIsRejected() {
            assertThatThrownBy(() -> ctx.upload.execute(ctx.workspaceId(),
                    ctx.csv("k-1", id(ctx.core.anasArchived), SEPTEMBER)))
                    .isInstanceOf(AccountArchivedException.class);
            assertThatThrownBy(() -> ctx.upload.execute(ctx.workspaceId(),
                    ctx.csv("k-2", id(ctx.core.bobsAccount), SEPTEMBER)))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    class ConfirmAndProcess {

        @Test
        void confirmEmitsOneEventEvenWhenRepeated() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();

            ImportBatchView confirmed = ctx.confirm.execute(ctx.workspaceId(), batch.id());
            ctx.confirm.execute(ctx.workspaceId(), batch.id());

            assertThat(confirmed.status()).isEqualTo(ImportStatus.CONFIRMED);
            assertThat(ctx.events).singleElement().satisfies(event -> {
                assertThat(event.eventType()).isEqualTo("import.confirmed");
                assertThat(event.workspaceId()).isEqualTo(ctx.workspaceId());
                assertThat(event.payload()).containsEntry("importId", batch.id().toString());
            });
        }

        @Test
        void processingCreatesPostedImportedTransactions() {
            ImportBatchView batch = confirmAndProcess("k-1", SEPTEMBER);

            ImportBatchView done = ctx.list.execute(ctx.workspaceId(), null, null).items().getFirst();
            assertThat(done.id()).isEqualTo(batch.id());
            assertThat(done.status()).isEqualTo(ImportStatus.COMPLETED);
            assertThat(done.importedLines()).isEqualTo(3);
            assertThat(done.completedAt()).isNotNull();

            List<Transaction> transactions = allTransactions();
            assertThat(transactions).hasSize(3).allSatisfy(transaction -> {
                assertThat(transaction.source()).isEqualTo(TransactionSource.IMPORT);
                assertThat(transaction.status()).isEqualTo(TransactionStatus.POSTED);
                assertThat(transaction.accountId()).isEqualTo(ctx.core.aurora.id());
            });
            assertThat(transactions).extracting(Transaction::type)
                    .containsExactlyInAnyOrder(TransactionType.INCOME, TransactionType.EXPENSE,
                            TransactionType.EXPENSE);

            List<ImportRecordView> records = ctx.listRecords.execute(ctx.workspaceId(), batch.id(), "IMPORTED",
                    null, null).items();
            assertThat(records).hasSize(3).allSatisfy(record -> assertThat(record.transactionId()).isNotNull());

            IntegrationEvent completed = ctx.events.getLast();
            assertThat(completed.eventType()).isEqualTo("import.completed");
            assertThat(completed.payload()).containsEntry("importedLines", 3);
        }

        @Test
        void reimportingTheSameFileImportsNothing() {
            confirmAndProcess("k-1", SEPTEMBER);

            ImportBatchView again = upload("k-2", SEPTEMBER).batch();

            assertThat(again.sameFileImportedBefore()).isTrue();
            assertThat(again.validLines()).isZero();
            assertThat(again.duplicateLines()).isEqualTo(3);
            List<ImportRecordView> duplicates = ctx.listRecords.execute(ctx.workspaceId(), again.id(), "DUPLICATE",
                    null, null).items();
            assertThat(duplicates).allSatisfy(record -> assertThat(record.transactionId()).isNotNull());

            processConfirmed(again.id());
            assertThat(allTransactions()).hasSize(3);
        }

        @Test
        void overlappingStatementImportsOnlyNewLines() {
            confirmAndProcess("k-1", SEPTEMBER);

            ImportBatchView overlap = confirmAndProcess("k-2", SEPTEMBER_OVERLAP);

            assertThat(overlap.duplicateLines()).isEqualTo(2);
            assertThat(allTransactions()).hasSize(5);
        }

        /** Dois previews do mesmo arquivo confirmados antes de qualquer processamento: só o primeiro importa. */
        @Test
        void lateDuplicatesAreDetectedAtProcessing() {
            ImportBatchView first = upload("k-1", SEPTEMBER).batch();
            ImportBatchView second = upload("k-2", SEPTEMBER).batch();
            ctx.confirm.execute(ctx.workspaceId(), first.id());
            ctx.confirm.execute(ctx.workspaceId(), second.id());

            processConfirmed(first.id());
            processConfirmed(second.id());

            ImportBatchView result = view(second.id());
            assertThat(result.status()).isEqualTo(ImportStatus.COMPLETED);
            assertThat(result.importedLines()).isZero();
            assertThat(result.duplicateLines()).isEqualTo(3);
            assertThat(allTransactions()).hasSize(3);
        }

        @Test
        void redeliveryHasNoEffect() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();
            ctx.confirm.execute(ctx.workspaceId(), batch.id());
            UUID eventId = UUID.randomUUID();

            ctx.process.execute(eventId, ctx.core.anasWorkspace.id(), new ImportBatchId(batch.id()));
            ctx.process.execute(eventId, ctx.core.anasWorkspace.id(), new ImportBatchId(batch.id()));
            ctx.process.execute(UUID.randomUUID(), ctx.core.anasWorkspace.id(), new ImportBatchId(batch.id()));

            assertThat(allTransactions()).hasSize(3);
            assertThat(ctx.events).extracting(IntegrationEvent::eventType)
                    .containsExactly("import.confirmed", "import.completed");
        }

        @Test
        void accountArchivedAfterPreviewFailsTheImport() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();
            ctx.confirm.execute(ctx.workspaceId(), batch.id());
            var aurora = ctx.core.accounts.findById(ctx.core.anasWorkspace.id(), ctx.core.aurora.id()).orElseThrow();
            aurora.archive(ctx.clock.instant());
            ctx.core.accounts.save(aurora);

            processConfirmed(batch.id());

            ImportBatchView failed = view(batch.id());
            assertThat(failed.status()).isEqualTo(ImportStatus.FAILED);
            assertThat(failed.failureReason()).isEqualTo("ACCOUNT_ARCHIVED");
            assertThat(allTransactions()).isEmpty();
            assertThat(ctx.keys.size()).isZero();
        }

        @Test
        void exhaustedRetriesMarkTheImportAsFailed() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();
            ctx.confirm.execute(ctx.workspaceId(), batch.id());

            ctx.fail.execute(ctx.core.anasWorkspace.id(), new ImportBatchId(batch.id()), FailImport.PROCESSING_ERROR);
            processConfirmed(batch.id());

            assertThat(view(batch.id()).status()).isEqualTo(ImportStatus.FAILED);
            assertThat(allTransactions()).isEmpty();
        }

        @Test
        void confirmingWithArchivedAccountIsRejected() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();
            var aurora = ctx.core.accounts.findById(ctx.core.anasWorkspace.id(), ctx.core.aurora.id()).orElseThrow();
            aurora.archive(ctx.clock.instant());
            ctx.core.accounts.save(aurora);

            assertThatThrownBy(() -> ctx.confirm.execute(ctx.workspaceId(), batch.id()))
                    .isInstanceOf(AccountArchivedException.class);
            assertThat(ctx.events).isEmpty();
        }
    }

    @Nested
    class CancelAndExpire {

        @Test
        void cancelDropsRecordsAndBlocksConfirmation() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();

            ImportBatchView cancelled = ctx.cancel.execute(ctx.workspaceId(), batch.id());
            ctx.cancel.execute(ctx.workspaceId(), batch.id());

            assertThat(cancelled.status()).isEqualTo(ImportStatus.CANCELLED);
            assertThat(ctx.records.all(ctx.core.anasWorkspace.id(), new ImportBatchId(batch.id()))).isEmpty();
            assertThatThrownBy(() -> ctx.confirm.execute(ctx.workspaceId(), batch.id()))
                    .isInstanceOf(InvalidImportStatusTransitionException.class);
        }

        @Test
        void confirmedImportCannotBeCancelled() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();
            ctx.confirm.execute(ctx.workspaceId(), batch.id());

            assertThatThrownBy(() -> ctx.cancel.execute(ctx.workspaceId(), batch.id()))
                    .isInstanceOf(InvalidImportStatusTransitionException.class);
        }

        @Test
        void previewsExpireAfter24Hours() {
            ImportBatchView old = upload("k-1", SEPTEMBER).batch();
            ctx.advance(Duration.ofHours(23));
            ImportBatchView recent = upload("k-2", SEPTEMBER_OVERLAP).batch();
            ctx.advance(Duration.ofHours(1));

            assertThat(ctx.expire.execute()).isEqualTo(1);

            assertThat(view(old.id()).status()).isEqualTo(ImportStatus.EXPIRED);
            assertThat(ctx.records.all(ctx.core.anasWorkspace.id(), new ImportBatchId(old.id()))).isEmpty();
            assertThat(view(recent.id()).status()).isEqualTo(ImportStatus.PREVIEW);
            assertThatThrownBy(() -> ctx.confirm.execute(ctx.workspaceId(), old.id()))
                    .isInstanceOf(InvalidImportStatusTransitionException.class);
        }
    }

    @Nested
    class Isolation {

        @Test
        void otherWorkspaceCannotSeeOrActOnTheImport() {
            ImportBatchView batch = upload("k-1", SEPTEMBER).batch();
            ctx.core.actAs(ctx.core.bob);
            UUID bobsWorkspace = ctx.core.bobsWorkspace.id().value();

            assertThatThrownBy(() -> ctx.confirm.execute(ctx.workspaceId(), batch.id()))
                    .isInstanceOf(WorkspaceNotFoundException.class);
            assertThatThrownBy(() -> ctx.confirm.execute(bobsWorkspace, batch.id()))
                    .isInstanceOf(ImportNotFoundException.class);
            assertThatThrownBy(() -> ctx.listRecords.execute(bobsWorkspace, batch.id(), null, null, null))
                    .isInstanceOf(ImportNotFoundException.class);
            assertThat(ctx.list.execute(bobsWorkspace, null, null).items()).isEmpty();
        }

        /** As chaves de dedupe são por conta: o mesmo extrato em outra conta não é duplicata. */
        @Test
        void sameStatementInAnotherAccountIsNotADuplicate() {
            confirmAndProcess("k-1", SEPTEMBER);

            UploadStatement.Result norte = ctx.upload.execute(ctx.workspaceId(),
                    ctx.csv("k-2", id(ctx.core.norte), SEPTEMBER));

            assertThat(norte.batch().validLines()).isEqualTo(3);
            assertThat(norte.batch().sameFileImportedBefore()).isFalse();
        }
    }

    private UploadStatement.Result upload(String key, String csv) {
        return ctx.upload.execute(ctx.workspaceId(), ctx.csv(key, id(ctx.core.aurora), csv));
    }

    private ImportBatchView confirmAndProcess(String key, String csv) {
        ImportBatchView batch = upload(key, csv).batch();
        ctx.confirm.execute(ctx.workspaceId(), batch.id());
        processConfirmed(batch.id());
        return view(batch.id());
    }

    private void processConfirmed(UUID importId) {
        ctx.process.execute(UUID.randomUUID(), ctx.core.anasWorkspace.id(), new ImportBatchId(importId));
    }

    private ImportBatchView view(UUID importId) {
        return ImportBatchView.from(ctx.batches.stored(new ImportBatchId(importId)), false);
    }

    private List<Transaction> allTransactions() {
        return ctx.core.transactions.search(ctx.core.anasWorkspace.id(),
                new TransactionCriteria(null, null, null, null, null, null), 0, 1000).items();
    }
}
