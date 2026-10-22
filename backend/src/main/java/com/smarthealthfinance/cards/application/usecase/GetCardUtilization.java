package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Utilização do limite (ADR-0025 §9, ADR-0026 §6). */
@Service
public class GetCardUtilization {

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;
    private final MeterRegistry meters;

    public GetCardUtilization(CardAccess access, CardLedgerLoader loader, BusinessCalendar calendar,
                              MeterRegistry meters) {
        this.access = access;
        this.loader = loader;
        this.calendar = calendar;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    public UtilizationView execute(UUID workspaceId, UUID cardId) {
        Timer.Sample sample = Timer.start(meters);
        CreditCard card = access.requireCard(workspaceId, cardId);
        CardLedgerSnapshot snapshot = loader.load(card, calendar.today());

        UtilizationView view = UtilizationView.from(snapshot.ledger().utilization(card.limit()), snapshot.today(),
                InvoiceCalendar.RULES_VERSION);
        sample.stop(Timer.builder("shf.cards.utilization.get").register(meters));
        return view;
    }
}
