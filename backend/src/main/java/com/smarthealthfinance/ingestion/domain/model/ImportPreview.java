package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Linhas de um arquivo prontas para o preview, com as chaves de deduplicação atribuídas (ADR-0009 §11).
 * <p>
 * A decisão final de duplicidade é do processamento (reserva da chave no banco); aqui ela é antecipada para o
 * usuário com o que já foi importado.
 */
public record ImportPreview(List<ImportRecord> records) {

    public ImportPreview {
        records = List.copyOf(records);
    }

    /** Chaves de todas as linhas válidas, para consultar o que já foi importado antes de montar o preview. */
    public static List<DedupeKey> keysOf(ParsedStatement statement) {
        return assignKeys(statement.lines()).values().stream().distinct().toList();
    }

    /**
     * @param alreadyImported chaves já importadas nesta conta e a transação de cada uma
     */
    public static ImportPreview of(ImportBatchId batchId, ParsedStatement statement,
                                   Map<DedupeKey, TransactionId> alreadyImported) {
        Objects.requireNonNull(alreadyImported, "alreadyImported");
        Map<StatementLine, DedupeKey> keys = assignKeys(statement.lines());

        List<ImportRecord> records = new ArrayList<>(statement.totalLines());
        Set<DedupeKey> seenInFile = new HashSet<>();
        for (StatementLine line : statement.lines()) {
            DedupeKey key = keys.get(line);
            if (alreadyImported.containsKey(key)) {
                records.add(ImportRecord.duplicate(batchId, line, key, alreadyImported.get(key)));
            }
            else if (!seenInFile.add(key)) {
                records.add(ImportRecord.duplicate(batchId, line, key, null));
            }
            else {
                records.add(ImportRecord.valid(batchId, line, key));
            }
        }
        statement.issues().forEach(issue -> records.add(ImportRecord.invalid(batchId, issue)));
        records.sort(Comparator.comparingInt(ImportRecord::lineNumber));
        return new ImportPreview(records);
    }

    public int total() {
        return records.size();
    }

    public int count(RecordStatus status) {
        return (int) records.stream().filter(record -> record.status() == status).count();
    }

    /** Período coberto pelas linhas válidas ou duplicadas (as que têm data). */
    public LocalDate firstDate() {
        return records.stream().filter(record -> record.line() != null).map(record -> record.line().occurredOn())
                .min(Comparator.naturalOrder()).orElse(null);
    }

    public LocalDate lastDate() {
        return records.stream().filter(record -> record.line() != null).map(record -> record.line().occurredOn())
                .max(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * Linhas com identificador do banco usam {@link DedupeKey#ofExternalId}; as demais, impressão digital com a
     * ordem da ocorrência da tupla (data, valor, descrição normalizada) no arquivo.
     */
    private static Map<StatementLine, DedupeKey> assignKeys(List<StatementLine> lines) {
        Map<StatementLine, DedupeKey> keys = new HashMap<>();
        Map<Fingerprint, Integer> occurrences = new HashMap<>();
        for (StatementLine line : lines) {
            if (line.externalId() != null) {
                keys.put(line, DedupeKey.ofExternalId(line.externalId(), line.occurredOn(), line.signedAmount()));
                continue;
            }
            Fingerprint fingerprint = new Fingerprint(line.occurredOn(), line.signedAmount(),
                    DedupeKey.normalize(line.description()));
            int occurrence = occurrences.merge(fingerprint, 1, Integer::sum);
            keys.put(line, DedupeKey.ofFingerprint(line.occurredOn(), line.signedAmount(), line.description(),
                    occurrence));
        }
        return keys;
    }

    private record Fingerprint(LocalDate occurredOn, Money signedAmount, String description) {
    }
}
