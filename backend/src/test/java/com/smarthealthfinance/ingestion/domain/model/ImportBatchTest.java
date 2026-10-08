package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.exception.InvalidImportStatusTransitionException;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportBatchTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final Currency BRL = Currency.getInstance("BRL");

    @Test
    void previewCarriesCountsAndPeriod() {
        ImportBatch batch = batch();

        assertThat(batch.status()).isEqualTo(ImportStatus.PREVIEW);
        assertThat(batch.totalLines()).isEqualTo(3);
        assertThat(batch.validLines()).isEqualTo(2);
        assertThat(batch.invalidLines()).isEqualTo(1);
        assertThat(batch.firstDate()).contains(LocalDate.of(2026, 9, 1));
        assertThat(batch.lastDate()).contains(LocalDate.of(2026, 9, 2));
        assertThat(batch.previewExpiresAt()).contains(NOW.plus(ImportBatch.PREVIEW_TTL));
    }

    @Test
    void confirmIsIdempotent() {
        ImportBatch batch = batch();

        assertThat(batch.confirm(NOW)).isTrue();
        assertThat(batch.confirm(NOW.plusSeconds(1))).isFalse();
        assertThat(batch.status()).isEqualTo(ImportStatus.CONFIRMED);
        assertThat(batch.confirmedAt()).contains(NOW);
        assertThat(batch.previewExpiresAt()).isEmpty();
    }

    @Test
    void expiredPreviewCannotBeConfirmed() {
        ImportBatch batch = batch();

        assertThatThrownBy(() -> batch.confirm(NOW.plus(ImportBatch.PREVIEW_TTL)))
                .isInstanceOf(InvalidImportStatusTransitionException.class);
    }

    @Test
    void cancelledPreviewCannotBeConfirmedAndConfirmedCannotBeCancelled() {
        ImportBatch cancelled = batch();
        cancelled.cancel(NOW);
        ImportBatch confirmed = batch();
        confirmed.confirm(NOW);

        assertThat(cancelled.cancel(NOW)).isFalse();
        assertThatThrownBy(() -> cancelled.confirm(NOW)).isInstanceOf(InvalidImportStatusTransitionException.class);
        assertThatThrownBy(() -> confirmed.cancel(NOW)).isInstanceOf(InvalidImportStatusTransitionException.class);
    }

    @Test
    void expiresOnlyPreviewsPastTheirWindow() {
        ImportBatch batch = batch();

        assertThat(batch.expire(NOW.plus(Duration.ofHours(23)))).isFalse();
        assertThat(batch.expire(NOW.plus(ImportBatch.PREVIEW_TTL))).isTrue();
        assertThat(batch.status()).isEqualTo(ImportStatus.EXPIRED);
    }

    @Test
    void completeTurnsLateDuplicatesIntoDuplicates() {
        ImportBatch batch = batch();
        batch.confirm(NOW);
        batch.startProcessing(NOW);
        batch.startProcessing(NOW);

        batch.complete(1, 1, NOW);

        assertThat(batch.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(batch.importedLines()).isEqualTo(1);
        assertThat(batch.validLines()).isEqualTo(1);
        assertThat(batch.duplicateLines()).isEqualTo(1);
        assertThat(batch.totalLines()).isEqualTo(batch.validLines() + batch.invalidLines() + batch.duplicateLines());
    }

    @Test
    void completeRequiresEveryValidLineAccountedFor() {
        ImportBatch batch = batch();
        batch.confirm(NOW);
        batch.startProcessing(NOW);

        assertThatThrownBy(() -> batch.complete(1, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failOnlyWhileAwaitingProcessing() {
        ImportBatch preview = batch();
        ImportBatch confirmed = batch();
        confirmed.confirm(NOW);

        confirmed.fail("ACCOUNT_ARCHIVED", NOW);
        confirmed.fail("OTHER", NOW);

        assertThat(confirmed.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(confirmed.failureReason()).contains("ACCOUNT_ARCHIVED");
        assertThatThrownBy(() -> preview.fail("X", NOW)).isInstanceOf(InvalidImportStatusTransitionException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "extrato.csv|extrato.csv",
            "C:\\Users\\ana\\Downloads\\extrato.ofx|extrato.ofx",
            "/home/ana/extrato.ofx|extrato.ofx",
            "'  '|extrato",
    })
    void normalizesFileName(String raw, String expected) {
        assertThat(ImportBatch.normalizeFileName(raw)).isEqualTo(expected);
    }

    @Test
    void truncatesLongFileName() {
        assertThat(ImportBatch.normalizeFileName("a".repeat(300) + ".csv")).hasSize(ImportBatch.MAX_FILE_NAME_LENGTH);
    }

    private static ImportBatch batch() {
        ImportBatchId id = ImportBatchId.generate(NOW);
        ParsedStatement statement = new ParsedStatement(ImportFormat.CSV, List.of(
                StatementLine.of(2, LocalDate.of(2026, 9, 1), Money.of("-5", BRL), "Café", null),
                StatementLine.of(3, LocalDate.of(2026, 9, 2), Money.of("100", BRL), "Pix", null)),
                List.of(new LineIssue(4, "amount", "INVALID_AMOUNT")));
        return ImportBatch.preview(id, new WorkspaceId(UUID.randomUUID()), new AccountId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()), ImportFormat.CSV, new ImportBatch.SourceFile("extrato.csv", 10, "a".repeat(64)),
                new IdempotencyKey("k-1"), "b".repeat(64), ImportPreview.of(id, statement, Map.of()), NOW);
    }
}
