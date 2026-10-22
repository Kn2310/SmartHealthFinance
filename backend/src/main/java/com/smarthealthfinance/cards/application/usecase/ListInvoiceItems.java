package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.InvoiceItemPageView;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.ItemPage;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.application.service.MonthParameter;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Lançamentos de uma fatura, paginados por offset como {@code /transactions} (ADR-0026 §4). */
@Service
public class ListInvoiceItems {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final CardLedgerReader reader;
    private final BusinessCalendar calendar;

    public ListInvoiceItems(CardAccess access, CardLedgerLoader loader, CardLedgerReader reader,
                            BusinessCalendar calendar) {
        this.access = access;
        this.loader = loader;
        this.reader = reader;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public InvoiceItemPageView execute(UUID workspaceId, UUID cardId, String dueMonthParameter, Integer page,
                                       Integer pageSize) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        YearMonth dueMonth = MonthParameter.required("dueMonth", dueMonthParameter);

        int pageNumber = page == null ? 0 : page;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (pageNumber < 0) {
            throw new InvalidValueException("page", "OUT_OF_RANGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidValueException("pageSize", "OUT_OF_RANGE");
        }

        CardLedgerSnapshot snapshot = loader.load(card, calendar.today());
        InvoiceDates dates = snapshot.calendar().byDueMonth(dueMonth).orElseThrow(InvoiceNotFoundException::new);
        snapshot.statement(dates).orElseThrow(InvoiceNotFoundException::new);

        CardInvoice row = snapshot.row(dates.closingMonth()).orElse(null);
        if (row == null) {
            return new InvoiceItemPageView(List.of(), pageNumber, size, 0);
        }

        ItemPage items = reader.items(card.workspaceId(), card.id(), row.id(), Math.multiplyExact(pageNumber, size),
                size);
        return new InvoiceItemPageView(items.items(), pageNumber, size, items.totalItems());
    }
}
