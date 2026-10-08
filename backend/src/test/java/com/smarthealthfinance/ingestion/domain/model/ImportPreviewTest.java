package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImportPreviewTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    private static final ImportBatchId BATCH = new ImportBatchId(UUID.randomUUID());

    @Test
    void identicalLinesWithoutIdAreDistinctLaunches() {
        ParsedStatement statement = statement(line(2, "-5", "Café", null), line(3, "-5", "Café", null));

        ImportPreview preview = ImportPreview.of(BATCH, statement, Map.of());

        assertThat(preview.records()).extracting(ImportRecord::status)
                .containsExactly(RecordStatus.VALID, RecordStatus.VALID);
        assertThat(preview.records().get(0).dedupeKey()).isNotEqualTo(preview.records().get(1).dedupeKey());
    }

    @Test
    void repeatedExternalIdInTheSameFileIsADuplicate() {
        ParsedStatement statement = statement(line(1, "-5", "Café", "F1"), line(2, "-5", "Café", "F1"));

        ImportPreview preview = ImportPreview.of(BATCH, statement, Map.of());

        assertThat(preview.records()).extracting(ImportRecord::status)
                .containsExactly(RecordStatus.VALID, RecordStatus.DUPLICATE);
        assertThat(preview.records().get(1).transactionId()).isNull();
    }

    @Test
    void alreadyImportedKeysAreDuplicatesPointingToTheExistingTransaction() {
        ParsedStatement statement = statement(line(1, "-5", "Café", "F1"), line(2, "-7", "Pão", "F2"));
        TransactionId existing = new TransactionId(UUID.randomUUID());
        DedupeKey known = DedupeKey.ofExternalId("F1", DAY, Money.of("-5", BRL));

        ImportPreview preview = ImportPreview.of(BATCH, statement, Map.of(known, existing));

        assertThat(preview.records().get(0).status()).isEqualTo(RecordStatus.DUPLICATE);
        assertThat(preview.records().get(0).transactionId()).isEqualTo(existing);
        assertThat(preview.records().get(1).status()).isEqualTo(RecordStatus.VALID);
    }

    /** Extrato de 1 a 15 importado; depois o de 10 a 30: os dias sobrepostos geram as mesmas chaves. */
    @Test
    void overlappingStatementProducesTheSameKeysForTheSameLines() {
        ParsedStatement first = statement(line(2, "-5", "Café", null), line(3, "-5", "Café", null),
                line(4, "-9", "Táxi", null));
        ParsedStatement overlapping = statement(line(2, "-5", "café", null), line(3, "-5", "CAFÉ", null),
                line(4, "-9", "Táxi", null), line(5, "-30", "Mercado", null));

        List<DedupeKey> firstKeys = ImportPreview.keysOf(first);
        List<DedupeKey> overlappingKeys = ImportPreview.keysOf(overlapping);

        assertThat(overlappingKeys).containsAll(firstKeys).hasSize(4);
    }

    @Test
    void invalidLinesKeepFileOrderAndCountsAddUp() {
        ParsedStatement statement = new ParsedStatement(ImportFormat.CSV,
                List.of(line(2, "-5", "Café", "F1"), line(4, "-5", "Café", "F1")),
                List.of(new LineIssue(3, "amount", "INVALID_AMOUNT")));

        ImportPreview preview = ImportPreview.of(BATCH, statement, Map.of());

        assertThat(preview.records()).extracting(ImportRecord::lineNumber).containsExactly(2, 3, 4);
        assertThat(preview.count(RecordStatus.VALID)).isEqualTo(1);
        assertThat(preview.count(RecordStatus.INVALID)).isEqualTo(1);
        assertThat(preview.count(RecordStatus.DUPLICATE)).isEqualTo(1);
        assertThat(preview.total()).isEqualTo(3);
        assertThat(preview.firstDate()).isEqualTo(DAY);
    }

    private static ParsedStatement statement(StatementLine... lines) {
        return new ParsedStatement(ImportFormat.CSV, List.of(lines), List.of());
    }

    private static StatementLine line(int number, String amount, String description, String externalId) {
        return StatementLine.of(number, DAY, Money.of(amount, BRL), description, externalId);
    }
}
