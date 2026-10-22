package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.application.service.MonthParameter;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Faturas de um intervalo de meses de vencimento (ADR-0026 §4): as que têm lançamentos, mais a atual, da mais recente
 * para a mais antiga. Padrão: 6 meses terminando na atual; no máximo 24.
 */
@Service
public class ListInvoices {

    public static final int DEFAULT_MONTHS = 6;
    public static final int MAX_MONTHS = 24;

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;

    public ListInvoices(CardAccess access, CardLedgerLoader loader, BusinessCalendar calendar) {
        this.access = access;
        this.loader = loader;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public List<InvoiceSummaryView> execute(UUID workspaceId, UUID cardId, String fromMonth, String toMonth) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        YearMonth from = MonthParameter.optional("from", fromMonth);
        YearMonth to = MonthParameter.optional("to", toMonth);
        CardLedgerSnapshot snapshot = loader.load(card, calendar.today());

        YearMonth last = to != null ? to : snapshot.current().dueMonth();
        YearMonth first = from != null ? from : last.minusMonths(DEFAULT_MONTHS - 1L);
        if (first.isAfter(last)) {
            throw new InvalidValueException("from", "AFTER_TO");
        }
        if (first.plusMonths(MAX_MONTHS).isBefore(last.plusMonths(1))) {
            throw new InvalidValueException("to", "RANGE_TOO_LONG");
        }

        return snapshot.ledger().statements().stream()
                .filter(statement -> within(statement, first, last)
                        || statement.dates().closingMonth().equals(snapshot.current().closingMonth()))
                .sorted(Comparator.comparing((InvoiceStatement statement) -> statement.dates().dueDate()).reversed())
                .map(statement -> InvoiceSummaryView.from(statement,
                        snapshot.row(statement.dates().closingMonth()).orElse(null)))
                .toList();
    }

    private static boolean within(InvoiceStatement statement, YearMonth first, YearMonth last) {
        YearMonth due = statement.dates().dueMonth();
        return !due.isBefore(first) && !due.isAfter(last);
    }
}
