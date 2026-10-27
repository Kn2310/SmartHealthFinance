package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.UtilizationHistoryView;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardUtilization;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evolução da utilização (ADR-0029 §2): o último dia de cada um dos meses anteriores e hoje, pelo razão do ADR-0025
 * calculado em cada data (adendo do §9, sem snapshot). Datas anteriores ao início do acompanhamento ficam fora.
 */
@Service
public class GetCardUtilizationHistory {

    static final int DEFAULT_MONTHS = 6;
    static final int MAX_MONTHS = 12;

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;
    private final MeterRegistry meters;

    public GetCardUtilizationHistory(CardAccess access, CardLedgerLoader loader, BusinessCalendar calendar,
                                     MeterRegistry meters) {
        this.access = access;
        this.loader = loader;
        this.calendar = calendar;
        this.meters = meters;
    }

    /** @param months meses anteriores a hoje (1–12, padrão 6) */
    @Transactional(readOnly = true)
    public UtilizationHistoryView execute(UUID workspaceId, UUID cardId, Integer months) {
        Timer.Sample sample = Timer.start(meters);
        CreditCard card = access.requireCard(workspaceId, cardId);
        int count = months == null ? DEFAULT_MONTHS : months;
        if (count < 1 || count > MAX_MONTHS) {
            throw new InvalidValueException("months", "OUT_OF_RANGE");
        }

        LocalDate today = calendar.today();
        List<LocalDate> dates = new ArrayList<>();
        YearMonth thisMonth = YearMonth.from(today);
        for (int back = count; back >= 1; back--) {
            dates.add(thisMonth.minusMonths(back).atEndOfMonth());
        }
        dates.add(today);

        Map<LocalDate, CardLedgerSnapshot> snapshots = loader.loadAt(card, dates);
        LocalDate trackedFrom = snapshots.get(today).calendar().datesOf(card.trackingStartMonth()).periodStart();

        List<UtilizationHistoryView.Point> points = new ArrayList<>();
        snapshots.forEach((date, snapshot) -> {
            if (date.isBefore(trackedFrom)) {
                return;
            }
            CardUtilization utilization = snapshot.ledger().utilization(card.limit());
            points.add(new UtilizationHistoryView.Point(date, utilization.utilized(), utilization.limit(),
                    utilization.utilizationPercent(), date.equals(today)));
        });

        UtilizationHistoryView view = new UtilizationHistoryView(List.copyOf(points), today,
                InvoiceCalendar.RULES_VERSION);
        sample.stop(Timer.builder("shf.cards.utilization.history.get").register(meters));
        return view;
    }
}
