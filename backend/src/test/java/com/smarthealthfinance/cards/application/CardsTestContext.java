package com.smarthealthfinance.cards.application;

import com.smarthealthfinance.accounts.application.InMemoryAccountRepository;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CreditFact;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.application.service.InvoiceAssignments;
import com.smarthealthfinance.cards.application.service.PaymentAccountCheck;
import com.smarthealthfinance.cards.application.usecase.ArchiveCard;
import com.smarthealthfinance.cards.application.usecase.CreateCard;
import com.smarthealthfinance.cards.application.usecase.GetCardUtilization;
import com.smarthealthfinance.cards.application.usecase.GetCardsSummary;
import com.smarthealthfinance.cards.application.usecase.GetFutureInvoices;
import com.smarthealthfinance.cards.application.usecase.GetInstallmentPlans;
import com.smarthealthfinance.cards.application.usecase.GetInvoice;
import com.smarthealthfinance.cards.application.usecase.ListCards;
import com.smarthealthfinance.cards.application.usecase.ListInvoiceItems;
import com.smarthealthfinance.cards.application.usecase.ListInvoices;
import com.smarthealthfinance.cards.application.usecase.NotifyClosedInvoices;
import com.smarthealthfinance.cards.application.usecase.ReactivateCard;
import com.smarthealthfinance.cards.application.usecase.UpdateCard;
import com.smarthealthfinance.cards.domain.model.CardInstallment;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;

/**
 * Ana e Bob, cada um com seu Workspace pessoal; as chamadas são feitas como Ana até {@link #actAs}. "Hoje" é terça,
 * 22 set 2026, a data de referência de 09-dados-de-exemplo.
 */
public final class CardsTestContext {

    public static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    public static final Currency BRL = Currency.getInstance("BRL");

    public final InMemoryUserRepository users = new InMemoryUserRepository();
    public final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    public final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    public final InMemoryCreditCardRepository cards = new InMemoryCreditCardRepository();
    public final InMemoryCardInvoiceRepository invoices = new InMemoryCardInvoiceRepository();
    public final FakeCardLedgerReader ledger = new FakeCardLedgerReader();
    public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final List<IntegrationEvent> outbox = new ArrayList<>();
    public final CardEvents events = new CardEvents(event -> {
        outbox.add(event);
        return UUID.randomUUID();
    });

    public final User ana = activeUser("sub-ana");
    public final User bob = activeUser("sub-bob");
    public final Workspace anasWorkspace = personalWorkspace(ana);
    public final Workspace bobsWorkspace = personalWorkspace(bob);

    private User actingUser = ana;

    public final CardAccess access = new CardAccess(new CurrentUserService(this::currentIdentity, users),
            new WorkspaceAccessGuard(workspaces), cards);
    public final BusinessCalendar calendar = new BusinessCalendar(clock, ZoneId.of("America/Sao_Paulo"));
    public final PaymentAccountCheck paymentAccounts = new PaymentAccountCheck(accounts);
    public final CardLedgerLoader loader = new CardLedgerLoader(invoices, ledger);
    public final InMemoryCardInstallmentRepository installments = new InMemoryCardInstallmentRepository();
    public final InvoiceAssignments assignments = new InvoiceAssignments(invoices, installments, clock);

    public final CreateCard createCard = new CreateCard(access, cards, paymentAccounts, clock, events);
    public final UpdateCard updateCard = new UpdateCard(access, cards, invoices, paymentAccounts, assignments,
            calendar, clock, events);
    public final ArchiveCard archiveCard = new ArchiveCard(access, cards, clock, events);
    public final ReactivateCard reactivateCard = new ReactivateCard(access, cards, clock, events);
    public final ListCards listCards = new ListCards(access, cards);
    public final GetCardsSummary summary = new GetCardsSummary(access, cards, loader, calendar, meters);
    public final ListInvoices listInvoices = new ListInvoices(access, loader, calendar);
    public final GetInvoice getInvoice = new GetInvoice(access, loader, calendar, meters);
    public final ListInvoiceItems listItems = new ListInvoiceItems(access, loader, calendar);
    public final GetFutureInvoices future = new GetFutureInvoices(access, loader, calendar);
    public final GetInstallmentPlans plans = new GetInstallmentPlans(access, loader, calendar);
    public final GetCardUtilization utilization = new GetCardUtilization(access, loader, calendar, meters);
    public final NotifyClosedInvoices notifyClosed = new NotifyClosedInvoices(invoices, events, calendar, clock,
            meters);

    public CardsTestContext() {
        users.store(ana);
        users.store(bob);
        workspaces.store(anasWorkspace);
        workspaces.store(bobsWorkspace);
    }

    public void actAs(User user) {
        actingUser = user;
    }

    public UUID anasWorkspaceId() {
        return anasWorkspace.id().value();
    }

    public UUID bobsWorkspaceId() {
        return bobsWorkspace.id().value();
    }

    public Account account(Workspace workspace, String name) {
        Account account = Account.create(AccountId.generate(NOW), workspace.id(), new AccountName(name),
                AccountType.CHECKING, null, BRL, true, NOW);
        accounts.store(account);
        return account;
    }

    public CreateCard.Command auroraCommand() {
        return new CreateCard.Command("Aurora Platinum", "Banco Aurora", "MASTERCARD", "4821", "12000.00", "BRL", 28, 5,
                null);
    }

    public CreditCard storedCard(UUID cardId) {
        return cards.findById(anasWorkspace.id(), new com.smarthealthfinance.cards.domain.valueobject.CardId(cardId))
                .orElseThrow();
    }

    /** A fatura do mês de fechamento, persistida pela regra atual do cartão se ainda não existir. */
    public CardInvoice invoiceRow(CreditCard card, YearMonth closingMonth) {
        List<CardInvoice> rows = invoices.findByCard(card.workspaceId(), card.id());
        for (CardInvoice row : rows) {
            if (row.dates().closingMonth().equals(closingMonth)) {
                return row;
            }
        }
        InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(), rows.stream().map(CardInvoice::dates).toList());
        CardInvoice invoice = new CardInvoice(CardInvoiceId.generate(NOW), card.workspaceId(), card.id(),
                calendar.datesOf(closingMonth), null, null);
        invoices.add(invoice);
        return invoice;
    }

    /** Uma compra à vista com o valor informado, na fatura do mês de fechamento. */
    public CardInvoice invoiceWith(CreditCard card, YearMonth closingMonth, String charges) {
        CardInvoice invoice = invoiceRow(card, closingMonth);
        UUID transaction = UUID.randomUUID();
        Money amount = Money.of(charges, BRL);
        ledger.installment(card.workspaceId(), card.id(), new InstallmentFact(transaction, 1, 1,
                invoice.dates().periodStart(), amount, invoice.id(), "Compras", null, null, "MANUAL"));
        installments.addAll(List.of(new CardInstallment(card.workspaceId(), card.id(), transaction, 1, 1,
                invoice.dates().periodStart(), amount, invoice.id(), InvoiceCalendar.RULES_VERSION)));
        return invoice;
    }

    /** Compra parcelada {@code POSTED}: parcela {@code k} na fatura do ciclo da compra deslocada {@code k − 1}. */
    public void purchase(CreditCard card, UUID transactionId, LocalDate purchasedOn, String description,
                         List<Money> parts) {
        for (int number = 1; number <= parts.size(); number++) {
            InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(),
                    invoices.findByCard(card.workspaceId(), card.id()).stream().map(CardInvoice::dates).toList());
            CardInvoice row = invoiceRow(card, calendar.installmentClosingMonth(purchasedOn, number));
            ledger.installment(card.workspaceId(), card.id(), new InstallmentFact(transactionId, number, parts.size(),
                    purchasedOn, parts.get(number - 1), row.id(), description, null, null, "MANUAL"));
            installments.addAll(List.of(new CardInstallment(card.workspaceId(), card.id(), transactionId, number,
                    parts.size(), purchasedOn, parts.get(number - 1), row.id(), InvoiceCalendar.RULES_VERSION)));
        }
    }

    public void credit(CreditCard card, LocalDate occurredOn, String amount, UUID refundOf) {
        ledger.credit(card.workspaceId(), card.id(), new CreditFact(UUID.randomUUID(), occurredOn,
                Money.of(amount, BRL), refundOf, "Estorno", null, null, "MANUAL"));
    }

    private AuthenticatedIdentity currentIdentity() {
        return new AuthenticatedIdentity(actingUser.externalIdentity(), actingUser.email().value(), null, null);
    }
}
