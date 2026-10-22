package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.FutureInvoicesView;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Charge;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Faturas futuras com o valor já comprometido em parcelas (ADR-0025 §10): da próxima fatura até a última parcela
 * contratada, sem a parte consumida por estornos. Nenhuma estimativa: previsão é do M9.
 */
@Service
public class GetFutureInvoices {

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;

    public GetFutureInvoices(CardAccess access, CardLedgerLoader loader, BusinessCalendar calendar) {
        this.access = access;
        this.loader = loader;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public FutureInvoicesView execute(UUID workspaceId, UUID cardId) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        CardLedgerSnapshot snapshot = loader.load(card, calendar.today());
        Money zero = Money.zero(card.limit().value().currency());
        YearMonth current = snapshot.current().closingMonth();

        Map<YearMonth, List<FutureInvoicesView.Installment>> byMonth = new TreeMap<>();
        List<InstallmentFact> facts = snapshot.facts().installments();
        for (int i = 0; i < facts.size(); i++) {
            Charge charge = snapshot.charges().get(i);
            Money committed = snapshot.allocation().netOf(charge);
            if (charge.closingMonth().isAfter(current) && committed.isPositive()) {
                InstallmentFact fact = facts.get(i);
                byMonth.computeIfAbsent(charge.closingMonth(), ignored -> new ArrayList<>())
                        .add(new FutureInvoicesView.Installment(fact.transactionId(), fact.description(),
                                fact.number(), fact.count(), committed));
            }
        }

        List<FutureInvoicesView.Month> months = new ArrayList<>();
        Money total = zero;
        for (Map.Entry<YearMonth, List<FutureInvoicesView.Installment>> entry : byMonth.entrySet()) {
            Money committed = entry.getValue().stream().map(FutureInvoicesView.Installment::amount)
                    .reduce(zero, Money::plus);
            months.add(new FutureInvoicesView.Month(snapshot.calendar().datesOf(entry.getKey()).dueMonth(), committed,
                    List.copyOf(entry.getValue())));
            total = total.plus(committed);
        }

        return new FutureInvoicesView(months, total, snapshot.today(), InvoiceCalendar.RULES_VERSION);
    }
}
