package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.FutureInvoicesView;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CommittedInstallment;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Faturas futuras com o valor já comprometido em parcelas (ADR-0025 §10): da próxima fatura até a última parcela
 * contratada. Nenhuma estimativa: previsão é do M9.
 */
@Service
public class GetFutureInvoices {

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final CardLedgerReader reader;
    private final BusinessCalendar calendar;

    public GetFutureInvoices(CardAccess access, CardLedgerLoader loader, CardLedgerReader reader,
                             BusinessCalendar calendar) {
        this.access = access;
        this.loader = loader;
        this.reader = reader;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public FutureInvoicesView execute(UUID workspaceId, UUID cardId) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        CardLedgerSnapshot snapshot = loader.load(card, calendar.today());
        Money zero = Money.zero(card.limit().value().currency());

        List<CardInvoice> future = snapshot.rows().stream()
                .filter(row -> row.dates().closingMonth().isAfter(snapshot.current().closingMonth()))
                .toList();
        List<CommittedInstallment> installments = reader.installments(card.workspaceId(), card.id(),
                future.stream().map(CardInvoice::id).toList());

        List<FutureInvoicesView.Month> months = new ArrayList<>();
        Money total = zero;
        for (CardInvoice row : future) {
            CardInvoiceId id = row.id();
            List<CommittedInstallment> inMonth = installments.stream()
                    .filter(installment -> installment.invoiceId().equals(id))
                    .toList();
            if (inMonth.isEmpty()) {
                continue;
            }
            Money committed = inMonth.stream().map(CommittedInstallment::amount).reduce(zero, Money::plus);
            months.add(new FutureInvoicesView.Month(row.dates().dueMonth(), committed, inMonth));
            total = total.plus(committed);
        }

        return new FutureInvoicesView(months, total, snapshot.today(), InvoiceCalendar.RULES_VERSION);
    }
}
