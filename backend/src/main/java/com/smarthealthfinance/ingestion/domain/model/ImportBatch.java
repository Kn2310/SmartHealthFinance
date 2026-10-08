package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.exception.InvalidImportStatusTransitionException;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
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
 */
public class ImportBatch {

    /** Previews não confirmados expiram (ADR-0009 §16). */
    public static final Duration PREVIEW_TTL = Duration.ofHours(24);

    public static final int MAX_FILE_NAME_LENGTH = 255;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final ImportBatchId id;
    private final WorkspaceId workspaceId;
    private final AccountId accountId;
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
    private int importedLines;
    private final LocalDate firstDate;
    private final LocalDate lastDate;
    private String failureReason;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    private Instant completedAt;
    private final long version;

    private ImportBatch(ImportBatchId id, WorkspaceId workspaceId, AccountId accountId, UserId createdBy,
                        ImportFormat format, ImportStatus status, String fileName, long fileSize, String fileSha256,
                        IdempotencyKey idempotencyKey, String requestHash, int totalLines, int validLines,
                        int invalidLines, int duplicateLines, int importedLines, LocalDate firstDate,
                        LocalDate lastDate, String failureReason, Instant createdAt, Instant updatedAt,
                        Instant confirmedAt, Instant completedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.format = Objects.requireNonNull(format, "format");
        this.status = Objects.requireNonNull(status, "status");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.fileSize = fileSize;
        this.fileSha256 = Objects.requireNonNull(fileSha256, "fileSha256");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.totalLines = totalLines;
        this.validLines = validLines;
        this.invalidLines = invalidLines;
        this.duplicateLines = duplicateLines;
        this.importedLines = importedLines;
        this.firstDate = firstDate;
        this.lastDate = lastDate;
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

    /** Novo batch em PREVIEW, com as contagens do preview. */
    public static ImportBatch preview(ImportBatchId id, WorkspaceId workspaceId, AccountId accountId, UserId createdBy,
                                      ImportFormat format, SourceFile file, IdempotencyKey idempotencyKey,
                                      String requestHash, ImportPreview preview, Instant now) {
        return new ImportBatch(id, workspaceId, accountId, createdBy, format, ImportStatus.PREVIEW,
                normalizeFileName(file.name()), file.size(), file.sha256(), idempotencyKey, requestHash,
                preview.total(), preview.count(RecordStatus.VALID), preview.count(RecordStatus.INVALID),
                preview.count(RecordStatus.DUPLICATE), 0, preview.firstDate(), preview.lastDate(), null, now, now,
                null, null, 0);
    }

    public static ImportBatch restore(ImportBatchId id, WorkspaceId workspaceId, AccountId accountId, UserId createdBy,
                                      ImportFormat format, ImportStatus status, String fileName, long fileSize,
                                      String fileSha256, IdempotencyKey idempotencyKey, String requestHash,
                                      int totalLines, int validLines, int invalidLines, int duplicateLines,
                                      int importedLines, LocalDate firstDate, LocalDate lastDate,
                                      String failureReason, Instant createdAt, Instant updatedAt,
                                      Instant confirmedAt, Instant completedAt, long version) {
        return new ImportBatch(id, workspaceId, accountId, createdBy, format, status, fileName, fileSize, fileSha256,
                idempotencyKey, requestHash, totalLines, validLines, invalidLines, duplicateLines, importedLines,
                firstDate, lastDate, failureReason, createdAt, updatedAt, confirmedAt, completedAt, version);
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

    /**
     * PROCESSING → COMPLETED.
     *
     * @param imported       linhas que viraram transação
     * @param lateDuplicates linhas VALID cuja chave foi reservada por outro batch depois do preview
     */
    public void complete(int imported, int lateDuplicates, Instant now) {
        if (status != ImportStatus.PROCESSING) {
            throw new InvalidImportStatusTransitionException(id, status, "complete");
        }
        if (imported < 0 || lateDuplicates < 0 || imported + lateDuplicates != validLines) {
            throw new IllegalArgumentException("Toda linha válida precisa virar importada ou duplicada");
        }
        validLines = imported;
        importedLines = imported;
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

    public ImportBatchId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public AccountId accountId() { return accountId; }
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
    public int importedLines() { return importedLines; }
    public Optional<LocalDate> firstDate() { return Optional.ofNullable(firstDate); }
    public Optional<LocalDate> lastDate() { return Optional.ofNullable(lastDate); }
    public Optional<String> failureReason() { return Optional.ofNullable(failureReason); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Optional<Instant> confirmedAt() { return Optional.ofNullable(confirmedAt); }
    public Optional<Instant> completedAt() { return Optional.ofNullable(completedAt); }
    public long version() { return version; }
}
