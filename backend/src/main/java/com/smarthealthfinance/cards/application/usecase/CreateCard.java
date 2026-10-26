package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.application.service.PaymentAccountCheck;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardLast4;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardNickname;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CreateCard {

    private static final Logger log = LoggerFactory.getLogger(CreateCard.class);

    private final CardAccess access;
    private final CreditCardRepository cards;
    private final PaymentAccountCheck paymentAccounts;
    private final Clock clock;
    private final BusinessCalendar calendar;
    private final CardEvents events;

    public CreateCard(CardAccess access, CreditCardRepository cards, PaymentAccountCheck paymentAccounts, Clock clock,
                      BusinessCalendar calendar, CardEvents events) {
        this.access = access;
        this.cards = cards;
        this.paymentAccounts = paymentAccounts;
        this.clock = clock;
        this.calendar = calendar;
        this.events = events;
    }

    /** Campos do cartão como vieram da API; a validação é do domínio. */
    public record Command(String nickname, String institutionName, String brand, String last4, String limitAmount,
                          String limitCurrency, int closingDay, int dueDay, UUID defaultPaymentAccountId) {}

    @Transactional
    public CardView execute(UUID workspaceId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Instant now = clock.instant();

        BillingCycle cycle = new BillingCycle(command.closingDay(), command.dueDay());
        CreditCard card = CreditCard.create(CardId.generate(now), workspace, new CardNickname(command.nickname()),
                InstitutionName.ofNullable(command.institutionName()).orElse(null), CardBrand.parse(command.brand()),
                new CardLast4(command.last4()),
                CardLimit.parse(command.limitAmount(), command.limitCurrency(), Workspace.DEFAULT_BASE_CURRENCY),
                cycle, paymentAccounts.requireActive(workspace, command.defaultPaymentAccountId()),
                // A fatura atual é a primeira acompanhada (ADR-0025 §15); só a importação a recua.
                new InvoiceCalendar(cycle, List.of()).closingMonthOf(calendar.today()), now);

        cards.add(card);
        events.created(card);

        log.info("Card created cardId={} workspaceId={}", card.id(), workspace);

        return CardView.from(card);
    }
}
