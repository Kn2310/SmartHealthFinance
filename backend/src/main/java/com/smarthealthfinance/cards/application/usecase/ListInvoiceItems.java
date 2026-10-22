package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.InvoiceItemPageView;
import com.smarthealthfinance.cards.application.dto.InvoiceItemView;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.application.service.MonthParameter;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Lançamentos de uma fatura, paginados por offset como {@code /transactions} (ADR-0026 §4). Os estornos já vêm
 * aplicados às parcelas (adendo do ADR-0025 §8); a fatura de um cartão é pequena, então a página é montada em memória.
 */
@Service
public class ListInvoiceItems {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;

    public ListInvoiceItems(CardAccess access, CardLedgerLoader loader, BusinessCalendar calendar) {
        this.access = access;
        this.loader = loader;
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

        List<InvoiceItemView> items = snapshot.items(dates.closingMonth());
        long offset = (long) pageNumber * size;
        List<InvoiceItemView> pageItems = offset >= items.size() ? List.of()
                : items.subList((int) offset, (int) Math.min(items.size(), offset + size));
        return new InvoiceItemPageView(List.copyOf(pageItems), pageNumber, size, items.size());
    }
}
