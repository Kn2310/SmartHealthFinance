package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.dto.InvoiceView;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.application.service.MonthParameter;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Detalhe de uma fatura pelo mês de vencimento, ou da atual (ADR-0026 §4). A atual e as futuras sempre existem;
 * fatura passada sem lançamentos → {@link InvoiceNotFoundException}.
 */
@Service
public class GetInvoice {

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;
    private final MeterRegistry meters;

    public GetInvoice(CardAccess access, CardLedgerLoader loader, BusinessCalendar calendar,
                      MeterRegistry meters) {
        this.access = access;
        this.loader = loader;
        this.calendar = calendar;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    public InvoiceView byDueMonth(UUID workspaceId, UUID cardId, String dueMonth) {
        Timer.Sample sample = Timer.start(meters);
        CardLedgerSnapshot snapshot = snapshot(workspaceId, cardId);
        InvoiceDates dates = snapshot.calendar().byDueMonth(MonthParameter.required("dueMonth", dueMonth))
                .orElseThrow(InvoiceNotFoundException::new);
        return timed(sample, view(snapshot, dates));
    }

    @Transactional(readOnly = true)
    public InvoiceView current(UUID workspaceId, UUID cardId) {
        Timer.Sample sample = Timer.start(meters);
        CardLedgerSnapshot snapshot = snapshot(workspaceId, cardId);
        return timed(sample, view(snapshot, snapshot.current()));
    }

    private CardLedgerSnapshot snapshot(UUID workspaceId, UUID cardId) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        return loader.load(card, calendar.today());
    }

    private InvoiceView view(CardLedgerSnapshot snapshot, InvoiceDates dates) {
        InvoiceStatement statement = snapshot.statement(dates).orElseThrow(InvoiceNotFoundException::new);
        CardInvoice row = snapshot.row(dates.closingMonth()).orElse(null);
        return new InvoiceView(InvoiceSummaryView.from(statement, row), snapshot.items(dates.closingMonth()).size(),
                snapshot.today(), InvoiceCalendar.RULES_VERSION);
    }

    private InvoiceView timed(Timer.Sample sample, InvoiceView view) {
        sample.stop(Timer.builder("shf.cards.invoice.get").register(meters));
        return view;
    }
}
