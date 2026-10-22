package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView.CardSummary;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardUtilization;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resumo dos cartões ativos (ADR-0026 §3): uma consulta de faturas e uma de somas para todos. */
@Service
public class GetCardsSummary {

    private final CardAccess access;
    private final CreditCardRepository cards;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;
    private final MeterRegistry meters;

    public GetCardsSummary(CardAccess access, CreditCardRepository cards, CardLedgerLoader loader,
                           BusinessCalendar calendar, MeterRegistry meters) {
        this.access = access;
        this.cards = cards;
        this.loader = loader;
        this.calendar = calendar;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    public CardsSummaryView execute(UUID workspaceId) {
        Timer.Sample sample = Timer.start(meters);
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        LocalDate today = calendar.today();

        List<CreditCard> active = cards.findAllByWorkspace(workspace).stream()
                .filter(card -> card.status() == CardStatus.ACTIVE)
                .toList();
        Map<CardId, CardLedgerSnapshot> snapshots = loader.loadAll(workspace, active, today);

        Money zero = Money.zero(Workspace.DEFAULT_BASE_CURRENCY);
        Money totalLimit = zero;
        Money utilized = zero;
        Money open = zero;
        LocalDate nextDue = null;
        List<CardSummary> summaries = new ArrayList<>();

        for (CreditCard card : active) {
            CardLedgerSnapshot snapshot = snapshots.get(card.id());
            CardUtilization utilization = snapshot.ledger().utilization(card.limit());

            totalLimit = totalLimit.plus(card.limit().value());
            utilized = utilized.plus(utilization.utilized());
            open = open.plus(snapshot.ledger().openRemaining());
            for (InvoiceStatement statement : snapshot.ledger().statements()) {
                if (statement.status() != InvoiceStatus.FUTURE && statement.remaining().isPositive()
                        && (nextDue == null || statement.dates().dueDate().isBefore(nextDue))) {
                    nextDue = statement.dates().dueDate();
                }
            }

            InvoiceStatement current = snapshot.currentStatement();
            summaries.add(new CardSummary(CardView.from(card),
                    InvoiceSummaryView.from(current, snapshot.row(current.dates().closingMonth()).orElse(null)),
                    UtilizationView.from(utilization, today, InvoiceCalendar.RULES_VERSION),
                    nextInvoiceCommitted(snapshot)));
        }

        CardsSummaryView view = new CardsSummaryView(totalLimit, utilized, open, nextDue, summaries, today,
                InvoiceCalendar.RULES_VERSION);
        sample.stop(Timer.builder("shf.cards.summary.get").register(meters));
        return view;
    }

    /** Valor já comprometido em parcelas na fatura seguinte à atual. */
    static Money nextInvoiceCommitted(CardLedgerSnapshot snapshot) {
        InvoiceDates next = snapshot.calendar().datesOf(snapshot.current().closingMonth().plusMonths(1));
        return snapshot.statement(next).map(InvoiceStatement::remaining)
                .orElse(Money.zero(snapshot.card().limit().value().currency()));
    }
}
