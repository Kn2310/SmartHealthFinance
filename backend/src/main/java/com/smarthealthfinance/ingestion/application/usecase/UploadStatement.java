package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;
import com.smarthealthfinance.ingestion.application.port.ImportedTransactionKeys;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.application.service.StatementFiles;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.model.ImportPreview;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.exception.IdempotencyKeyReusedException;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload de extrato: valida, lê e grava o preview, sem criar transações (ADR-0009 §3/§13).
 * <p>
 * Ordem: autorização → replay (se a Idempotency-Key já existe) → conta ativa → leitura do arquivo → preview com o
 * que já foi importado → claim da chave (o próprio insert do batch) → linhas. Tudo numa transação de banco.
 */
@Service
public class UploadStatement {

    private static final Logger log = LoggerFactory.getLogger(UploadStatement.class);

    private final ImportAccess access;
    private final StatementFiles files;
    private final ImportBatchRepository batches;
    private final ImportRecordRepository records;
    private final ImportedTransactionKeys keys;
    private final Clock clock;

    public UploadStatement(ImportAccess access, StatementFiles files, ImportBatchRepository batches,
                           ImportRecordRepository records, ImportedTransactionKeys keys, Clock clock) {
        this.access = access;
        this.files = files;
        this.batches = batches;
        this.records = records;
        this.keys = keys;
        this.clock = clock;
    }

    /** O arquivo nunca é guardado: só o hash, o nome e o tamanho. */
    public record Command(String idempotencyKey, UUID accountId, String fileName, byte[] content) {
    }

    /** @param replayed true quando a chave já tinha sido usada com o mesmo conteúdo (nada foi gravado) */
    public record Result(ImportBatchView batch, boolean replayed) {
    }

    /**
     * @throws com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException arquivo recusado inteiro
     */
    @Transactional
    public Result execute(UUID workspaceId, Command command) {
        ImportAccess.Actor actor = access.requireMember(workspaceId);
        WorkspaceId workspace = actor.workspaceId();
        IdempotencyKey key = new IdempotencyKey(command.idempotencyKey());
        if (command.accountId() == null) {
            throw new InvalidValueException("accountId", "REQUIRED");
        }
        AccountId accountId = new AccountId(command.accountId());
        byte[] content = command.content() == null ? new byte[0] : command.content();

        String fileSha256 = StatementFiles.sha256(content);
        String requestHash = StatementFiles.sha256(("v1|" + accountId + "|" + fileSha256)
                .getBytes(StandardCharsets.UTF_8));

        Optional<ImportBatch> previous = batches.findByIdempotencyKey(workspace, key);
        if (previous.isPresent()) {
            return replay(previous.get(), requestHash);
        }

        access.requireActiveAccount(workspace, accountId);
        StatementFiles.ReadFile file = files.read(command.fileName(), content);

        Instant now = clock.instant();
        ImportBatchId id = ImportBatchId.generate(now);
        ImportPreview preview = ImportPreview.of(id, file.statement(),
                keys.findExisting(workspace, accountId, ImportPreview.keysOf(file.statement())));
        ImportBatch batch = ImportBatch.preview(id, workspace, accountId, actor.userId(), file.format(),
                new ImportBatch.SourceFile(command.fileName(), content.length, fileSha256), key, requestHash,
                preview, now);

        // O insert do batch é o claim da chave: uma requisição concorrente com a mesma chave espera o commit e
        // vira replay.
        if (!batches.add(batch)) {
            return replay(batches.findByIdempotencyKey(workspace, key).orElseThrow(), requestHash);
        }
        records.addAll(workspace, preview.records());

        log.info("Import previewed importId={} workspaceId={} format={} lines={} valid={} invalid={} duplicates={}",
                batch.id(), workspace, batch.format(), batch.totalLines(), batch.validLines(), batch.invalidLines(),
                batch.duplicateLines());

        return new Result(view(batch), false);
    }

    private Result replay(ImportBatch batch, String requestHash) {
        if (!batch.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return new Result(view(batch), true);
    }

    private ImportBatchView view(ImportBatch batch) {
        return ImportBatchView.from(batch, batches.existsCompletedWithFile(batch.workspaceId(), batch.accountId(),
                batch.fileSha256(), batch.id()));
    }
}
