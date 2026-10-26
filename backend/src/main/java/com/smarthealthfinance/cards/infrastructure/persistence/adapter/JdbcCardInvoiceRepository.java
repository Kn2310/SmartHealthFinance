package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Datas das faturas (ADR-0026 §5). Os meses são gravados como o 1º dia do mês. */
@Repository
public class JdbcCardInvoiceRepository implements CardInvoiceRepository {

    private static final String COLUMNS = """
            id, workspace_id, card_id, closing_month, due_month, period_start, closing_date, due_date, dates_source,
            minimum_payment, currency, closed_notified_at
            """;

    private static final RowMapper<CardInvoice> MAPPER = (rs, row) -> {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        BigDecimal minimum = rs.getBigDecimal("minimum_payment");
        Timestamp notified = rs.getTimestamp("closed_notified_at");
        return new CardInvoice(
                new CardInvoiceId(rs.getObject("id", UUID.class)),
                new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
                new CardId(rs.getObject("card_id", UUID.class)),
                new InvoiceDates(
                        YearMonth.from(rs.getObject("closing_month", LocalDate.class)),
                        YearMonth.from(rs.getObject("due_month", LocalDate.class)),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("closing_date", LocalDate.class),
                        rs.getObject("due_date", LocalDate.class),
                        InvoiceDatesSource.valueOf(rs.getString("dates_source"))),
                minimum == null ? null : new Money(minimum, currency),
                notified == null ? null : notified.toInstant());
    };

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcCardInvoiceRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<CardInvoice> findByCard(WorkspaceId workspaceId, CardId cardId) {
        return jdbc.query("select " + COLUMNS + """
                 from card_invoices
                 where workspace_id = ? and card_id = ?
                 order by closing_month""", MAPPER, workspaceId.value(), cardId.value());
    }

    @Override
    public List<CardInvoice> findByWorkspace(WorkspaceId workspaceId) {
        return jdbc.query("select " + COLUMNS + """
                 from card_invoices
                 where workspace_id = ?
                 order by card_id, closing_month""", MAPPER, workspaceId.value());
    }

    @Override
    public void add(CardInvoice invoice) {
        InvoiceDates dates = invoice.dates();
        Instant now = clock.instant();
        jdbc.update("""
                insert into card_invoices (id, workspace_id, card_id, closing_month, due_month, period_start,
                  closing_date, due_date, dates_source, minimum_payment, currency, closed_notified_at, created_at,
                  updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                invoice.id().value(), invoice.workspaceId().value(), invoice.cardId().value(),
                month(dates.closingMonth()), month(dates.dueMonth()), Date.valueOf(dates.periodStart()),
                Date.valueOf(dates.closingDate()), Date.valueOf(dates.dueDate()), dates.source().name(),
                invoice.minimumPayment() == null ? null : invoice.minimumPayment().amount(),
                invoice.minimumPayment() == null ? "BRL" : invoice.minimumPayment().currency().getCurrencyCode(),
                invoice.closedNotifiedAt() == null ? null : Timestamp.from(invoice.closedNotifiedAt()),
                Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public void updateDates(List<CardInvoice> invoices) {
        Timestamp now = Timestamp.from(clock.instant());
        for (CardInvoice invoice : invoices) {
            InvoiceDates dates = invoice.dates();
            jdbc.update("""
                    update card_invoices
                    set due_month = ?, period_start = ?, closing_date = ?, due_date = ?, dates_source = ?,
                        updated_at = ?
                    where id = ? and workspace_id = ?""",
                    month(dates.dueMonth()), Date.valueOf(dates.periodStart()), Date.valueOf(dates.closingDate()),
                    Date.valueOf(dates.dueDate()), dates.source().name(), now, invoice.id().value(),
                    invoice.workspaceId().value());
        }
    }

    @Override
    public void updateStatement(CardInvoice invoice) {
        jdbc.update("""
                update card_invoices set due_date = ?, dates_source = ?, minimum_payment = ?, updated_at = ?
                where id = ? and workspace_id = ?""",
                Date.valueOf(invoice.dates().dueDate()), invoice.dates().source().name(),
                invoice.minimumPayment() == null ? null : invoice.minimumPayment().amount(),
                Timestamp.from(clock.instant()), invoice.id().value(), invoice.workspaceId().value());
    }

    @Override
    public void deleteAll(List<CardInvoice> invoices) {
        for (CardInvoice invoice : invoices) {
            jdbc.update("delete from card_invoices where id = ? and workspace_id = ?", invoice.id().value(),
                    invoice.workspaceId().value());
        }
    }

    @Override
    public List<CardInvoice> lockPendingClosed(LocalDate today, int limit) {
        return jdbc.query("select " + COLUMNS + """
                 from card_invoices
                 where closed_notified_at is null and closing_date <= ?
                   and closing_month >= (select c.tracking_start_month from credit_cards c
                                         where c.id = card_invoices.card_id
                                           and c.workspace_id = card_invoices.workspace_id)
                 order by closing_date, id
                 limit ?
                 for update skip locked""", MAPPER, Date.valueOf(today), limit);
    }

    @Override
    public void markClosedNotified(CardInvoice invoice, Instant notifiedAt) {
        jdbc.update("""
                update card_invoices set closed_notified_at = ?, updated_at = ?
                where id = ? and workspace_id = ? and closed_notified_at is null""",
                Timestamp.from(notifiedAt), Timestamp.from(notifiedAt), invoice.id().value(),
                invoice.workspaceId().value());
    }

    private static Date month(YearMonth month) {
        return Date.valueOf(month.atDay(1));
    }
}
