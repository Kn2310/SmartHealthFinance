package com.smarthealthfinance.ingestion.infrastructure.persistence.adapter;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportRecord;
import com.smarthealthfinance.ingestion.domain.model.LineIssue;
import com.smarthealthfinance.ingestion.domain.model.StatementLine;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Linhas do batch em JDBC com inserts/updates em lote (até 5.000 linhas por arquivo). */
@Repository
public class JdbcImportRecordRepository implements ImportRecordRepository {

    private static final int JDBC_BATCH = 500;

    private static final String COLUMNS = """
            batch_id, line_number, status, occurred_on, amount, currency, description, external_id, dedupe_key,
            issue_field, issue_code, transaction_id
            """;

    private static final RowMapper<ImportRecord> MAPPER = JdbcImportRecordRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcImportRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void addAll(WorkspaceId workspaceId, List<ImportRecord> records) {
        jdbc.batchUpdate("""
                        insert into import_records
                          (batch_id, workspace_id, line_number, status, occurred_on, amount, currency, description,
                           external_id, dedupe_key, issue_field, issue_code, transaction_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                records, JDBC_BATCH, (ps, record) -> {
                    StatementLine line = record.line();
                    ps.setObject(1, record.batchId().value());
                    ps.setObject(2, workspaceId.value());
                    ps.setInt(3, record.lineNumber());
                    ps.setString(4, record.status().name());
                    ps.setObject(5, line == null ? null : Date.valueOf(line.occurredOn()), Types.DATE);
                    ps.setBigDecimal(6, line == null ? null : line.signedAmount().amount());
                    ps.setString(7, line == null ? null : line.signedAmount().currency().getCurrencyCode());
                    ps.setString(8, line == null ? null : line.description());
                    ps.setString(9, line == null ? null : line.externalId());
                    ps.setString(10, record.dedupeKey() == null ? null : record.dedupeKey().value());
                    ps.setString(11, record.issue() == null ? null : record.issue().field());
                    ps.setString(12, record.issue() == null ? null : record.issue().code());
                    ps.setObject(13, record.transactionId() == null ? null : record.transactionId().value(),
                            Types.OTHER);
                });
    }

    @Override
    public ImportRecordPage search(WorkspaceId workspaceId, ImportBatchId batchId, RecordStatus status, int page,
                                   int pageSize) {
        List<Object> args = new ArrayList<>(List.of(workspaceId.value(), batchId.value()));
        String filter = "";
        if (status != null) {
            filter = " and status = ?";
            args.add(status.name());
        }
        Long total = jdbc.queryForObject("select count(*) from import_records where workspace_id = ? and batch_id = ?"
                + filter, Long.class, args.toArray());

        args.add(pageSize);
        args.add((long) page * pageSize);
        List<ImportRecord> items = jdbc.query("select " + COLUMNS
                + " from import_records where workspace_id = ? and batch_id = ?" + filter
                + " order by line_number limit ? offset ?", MAPPER, args.toArray());
        return new ImportRecordPage(items, total == null ? 0 : total);
    }

    @Override
    public List<ImportRecord> findAll(WorkspaceId workspaceId, ImportBatchId batchId, RecordStatus status) {
        return jdbc.query("select " + COLUMNS + """
                         from import_records where workspace_id = ? and batch_id = ? and status = ?
                        order by line_number
                        """,
                MAPPER, workspaceId.value(), batchId.value(), status.name());
    }

    @Override
    public void saveResults(WorkspaceId workspaceId, List<ImportRecord> records) {
        jdbc.batchUpdate("""
                        update import_records set status = ?, transaction_id = ?
                        where workspace_id = ? and batch_id = ? and line_number = ?
                        """,
                records, JDBC_BATCH, (ps, record) -> {
                    ps.setString(1, record.status().name());
                    ps.setObject(2, record.transactionId() == null ? null : record.transactionId().value(),
                            Types.OTHER);
                    ps.setObject(3, workspaceId.value());
                    ps.setObject(4, record.batchId().value());
                    ps.setInt(5, record.lineNumber());
                });
    }

    @Override
    public int deleteAll(WorkspaceId workspaceId, ImportBatchId batchId) {
        return jdbc.update("delete from import_records where workspace_id = ? and batch_id = ?", workspaceId.value(),
                batchId.value());
    }

    private static ImportRecord map(ResultSet rs, int row) throws SQLException {
        ImportBatchId batchId = new ImportBatchId(rs.getObject("batch_id", UUID.class));
        int lineNumber = rs.getInt("line_number");
        RecordStatus status = RecordStatus.valueOf(rs.getString("status"));
        UUID transactionId = rs.getObject("transaction_id", UUID.class);

        if (status == RecordStatus.INVALID) {
            return ImportRecord.invalid(batchId,
                    new LineIssue(lineNumber, rs.getString("issue_field"), rs.getString("issue_code")));
        }
        // Dados já validados e normalizados no upload: restaurados sem revalidar.
        StatementLine line = new StatementLine(lineNumber, rs.getDate("occurred_on").toLocalDate(),
                new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
                rs.getString("description"), rs.getString("external_id"));
        return new ImportRecord(batchId, lineNumber, status, line, new DedupeKey(rs.getString("dedupe_key")), null,
                transactionId == null ? null : new TransactionId(transactionId));
    }
}
