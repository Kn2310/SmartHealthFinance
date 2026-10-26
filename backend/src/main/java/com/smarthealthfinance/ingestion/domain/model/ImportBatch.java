package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.exception.InvalidImportStatusTransitionException;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement.StatementClosing;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Um arquivo enviado para importação (ADR-0009). O arquivo bruto nunca é guardado: só o hash e metadados.
 * As linhas ficam em {@link ImportRecord}, fora do agregado, porque podem ser milhares.
 * <p>
 * O destino é uma conta ou um cartão (§19). Numa fatura, linhas de pagamento ficam {@code SKIPPED} e parcelas de
 * compras já lançadas viram {@code RECONCILED} no processamento (§23–§26).
 */
public class ImportBatch {

    /** Previews não confirmados expiram (ADR-0009 §16). */
    public static final Duration PREVIEW_TTL = Duration.ofHours(24);

    public static final int MAX_FILE_NAME_LENGTH = 255;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final ImportBatchId id;
    private final WorkspaceId workspaceId;
    private final ImportDestination destination;
    private final UserId createdBy;
    private final ImportFormat format;
    private ImportStatus status;
    private final String fileName;
    private final long fileSize;
    private final String fileSha256;
    private final IdempotencyKey idempotencyKey;
    private final String requestHash;
    private final int totalLines;
    private int validLines;
    private final int invalidLines;
    private int duplicateLines;
    private final int skippedLines;
    private int importedLines;
    private int reconciledLines;
    private int categorizedLines;
    private final LocalDate firstDate;
    private final LocalDate lastDate;
    private final StatementClosing closing;
    private String failureReason;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    private Instant completedAt;
    private final long version;

    /**
     * Contagens de linhas como estão no banco: {@code total = valid + invalid + duplicate + skipped} e, depois do
     * processamento, {@code imported + reconciled ≤ valid}.
     */
    public record Counts(int total, int valid, int invalid, int duplicate, int skipped, int imported, int reconciled,
                         int categorized) {
    }

    private ImportBatch(ImportBatchId id, WorkspaceId workspaceId, ImportDestination destination, UserId createdBy,
                        ImportFormat format, ImportStatus status, String fileName, long fileSize, String fileSha256,
                        IdempotencyKey idempotencyKey, String requestHash, Counts counts, LocalDate firstDate,
                        LocalDate lastDate, StatementClosing closing, String failureReason, Instant createdAt,
                        Instant updatedAt, Instant confirmedAt, Instant completedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.format = Objects.requireNonNull(format, "format");
        this.status = Objects.requireNonNull(status, "status");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.fileSize = fileSize;
        this.fileSha256 = Objects.requireNonNull(fileSha256, "fileSha256");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(counts, "counts");
        this.totalLines = counts.total();
        this.validLines = counts.valid();
        this.invalidLines = counts.invalid();
        this.duplicateLines = counts.duplicate();
        this.skippedLines = counts.skipped();
        this.importedLines = counts.imported();
        this.reconciledLines = counts.reconciled();
        this.categorizedLines = counts.categorized();
        this.firstDate = firstDate;
        this.lastDate = lastDate;
        if (closing != null && !destination.isCard()) {
            throw new IllegalArgumentException("Dados de fatura só num batch de cartão");
        }
        this.closing = closing;
        this.failureReason = failureReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.confirmedAt = confirmedAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    /** Metadados do arquivo enviado. */
    public record SourceFile(String name, long size, String sha256) {
    }

    /**
     * Novo batch em PREVIEW, com as contagens do preview.
     *
     * @param closing dados da fatura informados no arquivo (só cartão), ou {@code null}
     */
    public static ImportBatch preview(ImportBatchId id, WorkspaceId workspaceId, ImportDestination destination,
                                      UserId createdBy, ImportFormat format, SourceFile file,
                                      IdempotencyKey idempotencyKey, String requestHash, ImportPreview preview,
                                      StatementClosing closing, Instant now) {
        Counts counts = new Counts(preview.total(), preview.count(RecordStatus.VALID),
                preview.count(RecordStatus.INVALID), preview.count(RecordStatus.DUPLICATE),
                preview.count(RecordStatus.SKIPPED), 0, 0, 0);
        return new ImportBatch(id, workspaceId, destination, createdBy, format, ImportStatus.PREVIEW,
                normalizeFileName(file.name()), file.size(), file.sha256(), idempotencyKey, requestHash, counts,
                preview.firstDate(), preview.lastDate(), closing, null, now, now, null, null, 0);
    }

    public static ImportBatch restore(ImportBatchId id, WorkspaceId workspaceId, ImportDestination destination,
                                      UserId createdBy, ImportFormat format, ImportStatus status, String fileName,
                                      long fileSize, String fileSha256, IdempotencyKey idempotencyKey,
                                      String requestHash, Counts counts, LocalDate firstDate, LocalDate lastDate,
                                      StatementClosing closing, String failureReason, Instant createdAt,
                                      Instant updatedAt, Instant confirmedAt, Instant completedAt, long version) {
        return new ImportBatch(id, workspaceId, destination, createdBy, format, status, fileName, fileSize,
                fileSha256, idempotencyKey, requestHash, counts, firstDate, lastDate, closing, failureReason,
                createdAt, updatedAt, confirmedAt, completedAt, version);
    }

    /**
     * PREVIEW → CONFIRMED. Repetir depois de confirmado não tem efeito.
     *
     * @return true se o status mudou (e o evento de confirmação deve ser emitido)
     */
    public boolean confirm(Instant now) {
        if (status.wasConfirmed()) {
            return false;
        }
        if (status != ImportStatus.PREVIEW || previewExpiredAt(now)) {
            throw new InvalidImportStatusTransitionException(id, status, "confirm");
        }
        status = ImportStatus.CONFIRMED;
        confirmedAt = now;
        updatedAt = now;
        return true;
    }

    /** PREVIEW → CANCELLED. Repetir não tem efeito; depois da confirmação, não há cancelamento. */
    public boolean cancel(Instant now) {
        if (status == ImportStatus.CANCELLED) {
            return false;
        }
        if (status != ImportStatus.PREVIEW) {
            throw new InvalidImportStatusTransitionException(id, status, "cancel");
        }
        status = ImportStatus.CANCELLED;
        updatedAt = now;
        return true;
    }

    /** PREVIEW vencido → EXPIRED. Em qualquer outro caso, nada muda. */
    public boolean expire(Instant now) {
        if (status != ImportStatus.PREVIEW || !previewExpiredAt(now)) {
            return false;
        }
        status = ImportStatus.EXPIRED;
        updatedAt = now;
        return true;
    }

    /** CONFIRMED → PROCESSING; numa reentrega já em PROCESSING, nada muda. */
    public void startProcessing(Instant now) {
        if (status == ImportStatus.PROCESSING) {
            return;
        }
        if (status != ImportStatus.CONFIRMED) {
            throw new InvalidImportStatusTransitionException(id, status, "startProcessing");
        }
        status = ImportStatus.PROCESSING;
        updatedAt = now;
    }

    /** PROCESSING → COMPLETED sem conciliação (extrato de conta). */
    public void complete(int imported, int lateDuplicates, int categorized, Instant now) {
        complete(imported, 0, lateDuplicates, categorized, now);
    }

    /**
     * PROCESSING → COMPLETED.
     *
     * @param imported       linhas que viraram transação
     * @param reconciled     parcelas conciliadas com uma compra já lançada (só no cartão, ADR-0009 §26)
     * @param lateDuplicates linhas VALID cuja chave foi reservada por outro batch depois do preview
     * @param categorized    das importadas, as que as regras categorizaram (ADR-0013 §4)
     */
    public void complete(int imported, int reconciled, int lateDuplicates, int categorized, Instant now) {
        if (status != ImportStatus.PROCESSING) {
            throw new InvalidImportStatusTransitionException(id, status, "complete");
        }
        if (categorized < 0 || categorized > imported) {
            throw new IllegalArgumentException("Categorizadas não podem passar das importadas");
        }
        if (reconciled > 0 && !destination.isCard()) {
            throw new IllegalArgumentException("Só a fatura de cartão concilia parcelas");
        }
        if (imported < 0 || reconciled < 0 || lateDuplicates < 0
                || imported + reconciled + lateDuplicates != validLines) {
            throw new IllegalArgumentException("Toda linha válida precisa virar importada, conciliada ou duplicada");
        }
        validLines = imported + reconciled;
        importedLines = imported;
        reconciledLines = reconciled;
        categorizedLines = categorized;
        duplicateLines += lateDuplicates;
        status = ImportStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    /** CONFIRMED/PROCESSING → FAILED. Repetir não tem efeito. */
    public void fail(String reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        if (status == ImportStatus.FAILED) {
            return;
        }
        if (!status.awaitsProcessing()) {
            throw new InvalidImportStatusTransitionException(id, status, "fail");
        }
        status = ImportStatus.FAILED;
        failureReason = reason;
        completedAt = now;
        updatedAt = now;
    }

    public boolean previewExpiredAt(Instant now) {
        return !now.isBefore(createdAt.plus(PREVIEW_TTL));
    }

    /** Até quando o preview pode ser confirmado; vazio fora de PREVIEW. */
    public Optional<Instant> previewExpiresAt() {
        return status == ImportStatus.PREVIEW ? Optional.of(createdAt.plus(PREVIEW_TTL)) : Optional.empty();
    }

    /** Remove diretórios (alguns navegadores enviam o caminho completo) e controles; corta no limite da coluna. */
    static String normalizeFileName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = CONTROL.matcher(name).replaceAll("").strip();
        if (name.isEmpty()) {
            return "extrato";
        }
        return name.length() <= MAX_FILE_NAME_LENGTH ? name : name.substring(0, MAX_FILE_NAME_LENGTH);
    }

    public Counts counts() {
        return new Counts(totalLines, validLines, invalidLines, duplicateLines, skippedLines, importedLines,
                reconciledLines, categorizedLines);
    }

    public ImportBatchId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public ImportDestination destination() { return destination; }
    public UserId createdBy() { return createdBy; }
    public ImportFormat format() { return format; }
    public ImportStatus status() { return status; }
    public String fileName() { return fileName; }
    public long fileSize() { return fileSize; }
    public String fileSha256() { return fileSha256; }
    public IdempotencyKey idempotencyKey() { return idempotencyKey; }
    public String requestHash() { return requestHash; }
    public int totalLines() { return totalLines; }
    public int validLines() { return validLines; }
    public int invalidLines() { return invalidLines; }
    public int duplicateLines() { return duplicateLines; }
    public int skippedLines() { return skippedLines; }
    public int importedLines() { return importedLines; }
    public int reconciledLines() { return reconciledLines; }
    public int categorizedLines() { return categorizedLines; }
    public Optional<LocalDate> firstDate() { return Optional.ofNullable(firstDate); }
    public Optional<LocalDate> lastDate() { return Optional.ofNullable(lastDate); }
    public Optional<StatementClosing> closing() { return Optional.ofNullable(closing); }
    public Optional<String> failureReason() { return Optional.ofNullable(failureReason); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Optional<Instant> confirmedAt() { return Optional.ofNullable(confirmedAt); }
    public Optional<Instant> completedAt() { return Optional.ofNullable(completedAt); }
    public long version() { return version; }
}
