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
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
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
 * Ordem: autorização → replay (se a Idempotency-Key já existe) → conta ou cartão ativo → leitura do arquivo → preview com o
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

    /**
     * O arquivo nunca é guardado: só o hash, o nome e o tamanho.
     *
     * @param accountId conta de destino, ou nula
     * @param cardId    cartão de destino, ou nulo; exatamente um dos dois (ADR-0009 §19)
     */
    public record Command(String idempotencyKey, UUID accountId, UUID cardId, String fileName, byte[] content) {
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
        ImportDestination destination = destinationOf(command);
        byte[] content = command.content() == null ? new byte[0] : command.content();

        String fileSha256 = StatementFiles.sha256(content);
        // Conta: o formato v1 de sempre; cartão: o destino entra na chave (ADR-0009 §19).
        String material = destination.isCard()
                ? "v1|card|" + destination.id() + "|" + fileSha256
                : "v1|" + destination.id() + "|" + fileSha256;
        String requestHash = StatementFiles.sha256(material.getBytes(StandardCharsets.UTF_8));

        Optional<ImportBatch> previous = batches.findByIdempotencyKey(workspace, key);
        if (previous.isPresent()) {
            return replay(previous.get(), requestHash);
        }

        access.requireAvailable(workspace, destination);
        StatementFiles.ReadFile file = files.read(command.fileName(), content, destination.kind());

        Instant now = clock.instant();
        ImportBatchId id = ImportBatchId.generate(now);
        ImportPreview preview = ImportPreview.of(id, file.statement(),
                keys.findExisting(workspace, destination, ImportPreview.keysOf(file.statement())),
                destination.kind());
        ImportBatch batch = ImportBatch.preview(id, workspace, destination, actor.userId(), file.format(),
                new ImportBatch.SourceFile(command.fileName(), content.length, fileSha256), key, requestHash,
                preview, file.statement().closing(), now);

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

    /** Uma conta ou um cartão; sem nenhum, a conta continua obrigatória como antes. */
    private static ImportDestination destinationOf(Command command) {
        if (command.accountId() != null && command.cardId() != null) {
            throw new InvalidValueException("destination", "EXACTLY_ONE");
        }
        if (command.cardId() != null) {
            return ImportDestination.card(command.cardId());
        }
        if (command.accountId() == null) {
            throw new InvalidValueException("accountId", "REQUIRED");
        }
        return ImportDestination.account(new AccountId(command.accountId()));
    }

    private Result replay(ImportBatch batch, String requestHash) {
        if (!batch.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return new Result(view(batch), true);
    }

    private ImportBatchView view(ImportBatch batch) {
        return ImportBatchView.from(batch, batches.existsCompletedWithFile(batch.workspaceId(), batch.destination(),
                batch.fileSha256(), batch.id()));
    }
}
