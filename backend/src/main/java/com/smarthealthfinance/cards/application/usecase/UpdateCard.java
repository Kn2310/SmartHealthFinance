package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.application.service.InvoiceAssignments;
import com.smarthealthfinance.cards.application.service.PaymentAccountCheck;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardLast4;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardNickname;
import com.smarthealthfinance.identity.domain.model.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Substituição completa dos campos editáveis (ADR-0026 §2). Mudar o ciclo recalcula as datas das faturas ainda não
 * fechadas, na mesma transação; as fechadas ficam congeladas (ADR-0025 §5).
 */
@Service
public class UpdateCard {

    private static final Logger log = LoggerFactory.getLogger(UpdateCard.class);

    private final CardAccess access;
    private final CreditCardRepository cards;
    private final CardInvoiceRepository invoices;
    private final PaymentAccountCheck paymentAccounts;
    private final InvoiceAssignments assignments;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final CardEvents events;

    public UpdateCard(CardAccess access, CreditCardRepository cards, CardInvoiceRepository invoices,
                      PaymentAccountCheck paymentAccounts, InvoiceAssignments assignments, BusinessCalendar calendar,
                      Clock clock, CardEvents events) {
        this.access = access;
        this.cards = cards;
        this.invoices = invoices;
        this.paymentAccounts = paymentAccounts;
        this.assignments = assignments;
        this.calendar = calendar;
        this.clock = clock;
        this.events = events;
    }

    /** institutionName em branco remove a instituição; defaultPaymentAccountId nulo remove a conta padrão. */
    public record Command(String nickname, String institutionName, String brand, String last4, String limitAmount,
                          String limitCurrency, int closingDay, int dueDay, UUID defaultPaymentAccountId) {}

    @Transactional
    public CardView execute(UUID workspaceId, UUID cardId, Command command) {
        CreditCard card = access.requireCard(workspaceId, cardId);

        BillingCycle newCycle = new BillingCycle(command.closingDay(), command.dueDay());
        CardNickname nickname = new CardNickname(command.nickname());
        InstitutionName institution = InstitutionName.ofNullable(command.institutionName()).orElse(null);
        CardBrand brand = CardBrand.parse(command.brand());
        CardLast4 last4 = new CardLast4(command.last4());
        CardLimit limit = CardLimit.parse(command.limitAmount(), command.limitCurrency(),
                Workspace.DEFAULT_BASE_CURRENCY);

        // Só uma conta padrão nova precisa estar ativa: editar outro campo não obriga a trocar a conta já escolhida.
        AccountId current = card.defaultPaymentAccountId().orElse(null);
        AccountId account = command.defaultPaymentAccountId() == null ? null
                : new AccountId(command.defaultPaymentAccountId());
        if (account != null && !Objects.equals(account, current)) {
            paymentAccounts.requireActive(card.workspaceId(), account.value());
        }

        boolean cycleChanged = !newCycle.equals(card.cycle());
        List<CardInvoice> recalculated = List.of();
        if (cycleChanged) {
            card.ensureActive();
            recalculated = InvoiceCalendar.recalculateOpen(invoices.findByCard(card.workspaceId(), card.id()),
                    newCycle, calendar.today());
        }

        boolean changed = card.updateDetails(nickname, institution, brand, last4, limit, newCycle, account,
                clock.instant());
        cards.save(card);
        invoices.updateDates(recalculated);
        int moved = cycleChanged ? assignments.reassignOpen(card, calendar.today()) : 0;
        if (changed) {
            events.updated(card);
            log.info("Card updated cardId={} workspaceId={} invoicesRecalculated={} installmentsMoved={}", card.id(),
                    card.workspaceId(), recalculated.size(), moved);
        }

        return CardView.from(card);
    }
}
