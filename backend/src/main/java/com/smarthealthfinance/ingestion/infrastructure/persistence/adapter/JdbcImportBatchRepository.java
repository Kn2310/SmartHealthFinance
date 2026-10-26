package com.smarthealthfinance.ingestion.infrastructure.persistence.adapter;

import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.enums.StatementKind;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement.StatementClosing;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC em vez de JPA: o claim da Idempotency-Key é o próprio {@code INSERT ... ON CONFLICT DO NOTHING} e o lock
 * otimista é um {@code UPDATE ... WHERE version = ?}. O JdbcTemplate participa da transação do
 * JpaTransactionManager.
 */
@Repository
public class JdbcImportBatchRepository implements ImportBatchRepository {

    private static final String COLUMNS = """
            id, workspace_id, account_id, card_id, created_by, format, status, file_name, file_size, file_sha256,
            idempotency_key, request_hash, total_lines, valid_lines, invalid_lines, duplicate_lines, skipped_lines,
            imported_lines, reconciled_lines, categorized_lines, first_date, last_date, statement_due_date,
            statement_minimum_payment, failure_reason, created_at, updated_at, confirmed_at, completed_at, version
            """;

    private static final RowMapper<ImportBatch> MAPPER = JdbcImportBatchRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcImportBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ImportBatch> findById(WorkspaceId workspaceId, ImportBatchId id) {
        return jdbc.query("select " + COLUMNS + " from import_batches where id = ? and workspace_id = ?", MAPPER,
                id.value(), workspaceId.value()).stream().findFirst();
    }

    @Override
    public Optional<ImportBatch> findByIdForUpdate(WorkspaceId workspaceId, ImportBatchId id) {
        return jdbc.query("select " + COLUMNS + " from import_batches where id = ? and workspace_id = ? for update",
                MAPPER, id.value(), workspaceId.value()).stream().findFirst();
    }

    @Override
    public Optional<ImportBatch> findByIdempotencyKey(WorkspaceId workspaceId, IdempotencyKey key) {
        return jdbc.query("select " + COLUMNS + " from import_batches where workspace_id = ? and idempotency_key = ?",
                MAPPER, workspaceId.value(), key.value()).stream().findFirst();
    }

    @Override
    public boolean add(ImportBatch batch) {
        ImportDestination destination = batch.destination();
        Optional<StatementClosing> closing = batch.closing();
        return jdbc.update("insert into import_batches (" + COLUMNS + """
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (workspace_id, idempotency_key) do nothing
                        """,
                batch.id().value(), batch.workspaceId().value(), destination.isCard() ? null : destination.id(),
                destination.isCard() ? destination.id() : null, batch.createdBy().value(), batch.format().name(),
                batch.status().name(), batch.fileName(), batch.fileSize(), batch.fileSha256(),
                batch.idempotencyKey().value(), batch.requestHash(), batch.totalLines(), batch.validLines(),
                batch.invalidLines(), batch.duplicateLines(), batch.skippedLines(), batch.importedLines(),
                batch.reconciledLines(), batch.categorizedLines(), date(batch.firstDate()), date(batch.lastDate()),
                date(closing.map(StatementClosing::dueDate)),
                closing.map(StatementClosing::minimumPayment).map(Money::amount).orElse(null),
                batch.failureReason().orElse(null), Timestamp.from(batch.createdAt()),
                Timestamp.from(batch.updatedAt()), timestamp(batch.confirmedAt()), timestamp(batch.completedAt()),
                batch.version()) == 1;
    }

    @Override
    public void save(ImportBatch batch) {
        int updated = jdbc.update("""
                        update import_batches
                        set status = ?, valid_lines = ?, duplicate_lines = ?, imported_lines = ?, reconciled_lines = ?,
                            categorized_lines = ?, failure_reason = ?, updated_at = ?, confirmed_at = ?,
                            completed_at = ?, version = version + 1
                        where id = ? and workspace_id = ? and version = ?
                        """,
                batch.status().name(), batch.validLines(), batch.duplicateLines(), batch.importedLines(),
                batch.reconciledLines(), batch.categorizedLines(), batch.failureReason().orElse(null),
                Timestamp.from(batch.updatedAt()), timestamp(batch.confirmedAt()), timestamp(batch.completedAt()),
                batch.id().value(), batch.workspaceId().value(), batch.version());
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Import batch changed concurrently: " + batch.id());
        }
    }

    @Override
    public ImportBatchPage search(WorkspaceId workspaceId, int page, int pageSize) {
        List<ImportBatch> items = jdbc.query("select " + COLUMNS + """
                         from import_batches where workspace_id = ?
                        order by created_at desc, id desc
                        limit ? offset ?
                        """,
                MAPPER, workspaceId.value(), pageSize, (long) page * pageSize);
        Long total = jdbc.queryForObject("select count(*) from import_batches where workspace_id = ?", Long.class,
                workspaceId.value());
        return new ImportBatchPage(items, total == null ? 0 : total);
    }

    @Override
    public List<ImportBatch> findPreviewsCreatedUpTo(Instant createdUpTo, int limit) {
        return jdbc.query("select " + COLUMNS + """
                         from import_batches where status = 'PREVIEW' and created_at <= ?
                        order by created_at
                        limit ?
                        """,
                MAPPER, Timestamp.from(createdUpTo), limit);
    }

    @Override
    public boolean existsCompletedWithFile(WorkspaceId workspaceId, ImportDestination destination, String fileSha256,
                                           ImportBatchId except) {
        String column = destination.isCard() ? "card_id" : "account_id";
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists (
                          select 1 from import_batches
                          where workspace_id = ? and %s = ? and file_sha256 = ? and status = 'COMPLETED'
                            and id <> ?)
                        """.formatted(column),
                Boolean.class, workspaceId.value(), destination.id(), fileSha256, except.value()));
    }

    private static ImportBatch map(ResultSet rs, int row) throws SQLException {
        UUID cardId = rs.getObject("card_id", UUID.class);
        ImportDestination destination = cardId != null ? ImportDestination.card(cardId)
                : new ImportDestination(StatementKind.ACCOUNT, rs.getObject("account_id", UUID.class));
        LocalDate dueDate = localDate(rs.getDate("statement_due_date"));
        BigDecimal minimum = rs.getBigDecimal("statement_minimum_payment");
        StatementClosing closing = dueDate == null && minimum == null ? null : new StatementClosing(dueDate,
                minimum == null ? null : new Money(minimum, Workspace.DEFAULT_BASE_CURRENCY));
        return ImportBatch.restore(
                new ImportBatchId(rs.getObject("id", UUID.class)),
                new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
                destination,
                new UserId(rs.getObject("created_by", UUID.class)),
                ImportFormat.valueOf(rs.getString("format")),
                ImportStatus.valueOf(rs.getString("status")),
                rs.getString("file_name"),
                rs.getLong("file_size"),
                rs.getString("file_sha256"),
                new IdempotencyKey(rs.getString("idempotency_key")),
                rs.getString("request_hash"),
                new ImportBatch.Counts(rs.getInt("total_lines"), rs.getInt("valid_lines"),
                        rs.getInt("invalid_lines"), rs.getInt("duplicate_lines"), rs.getInt("skipped_lines"),
                        rs.getInt("imported_lines"), rs.getInt("reconciled_lines"), rs.getInt("categorized_lines")),
                localDate(rs.getDate("first_date")),
                localDate(rs.getDate("last_date")),
                closing,
                rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("confirmed_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getLong("version"));
    }

    private static Date date(Optional<LocalDate> value) {
        return value.map(Date::valueOf).orElse(null);
    }

    private static Timestamp timestamp(Optional<Instant> value) {
        return value.map(Timestamp::from).orElse(null);
    }

    private static LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
