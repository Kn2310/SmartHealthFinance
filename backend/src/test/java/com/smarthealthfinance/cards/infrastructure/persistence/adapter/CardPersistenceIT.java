package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CardFacts;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CreditFact;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.model.CardInstallment;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInstallmentRepository;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.cards.domain.valueobject.CardLast4;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardNickname;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Persistência de Cartões e paridade das consultas de fatos (ADR-0026 §7 e §10): o que {@link JdbcCardLedgerReader}
 * devolve é comparado com uma referência em memória sobre os mesmos fatos, com todos os status, dois Workspaces,
 * dois cartões por Workspace, compras parceladas, estornos e pagamentos.
 */
class CardPersistenceIT extends IntegrationTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    private static final String[] CARD_STATUSES = {"POSTED", "POSTED", "POSTED", "REVERSED"};
    private static final String[] ACCOUNT_STATUSES = {"POSTED", "POSTED", "PENDING", "CANCELLED", "REVERSED"};

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CreditCardRepository cards;

    @Autowired
    CardInvoiceRepository invoices;

    @Autowired
    CardInstallmentRepository installments;

    @Autowired
    CardLedgerReader reader;

    @Autowired
    TransactionTemplate transactions;

    private Owner ana;
    private Owner bob;

    record Owner(WorkspaceId workspace, UUID account) {}

    /** Fatos gravados pelo teste, com o status da transação. */
    record Purchase(CardId card, UUID transaction, String status, List<InstallmentFact> installments) {}

    record Credit(CardId card, String status, CreditFact fact) {}

    record Payment(CardId card, String status, BigDecimal amount) {}

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        ana = owner(identity("sub-ana", "ana@example.com"));
        bob = owner(identity("sub-bob", "bob@example.com"));
    }

    @Test
    void cardRoundTripIsolationAndOptimisticLock() {
        CreditCard card = card(ana, "Aurora Platinum");
        cards.add(card);

        CreditCard loaded = cards.findById(ana.workspace(), card.id()).orElseThrow();
        assertThat(loaded.nickname().value()).isEqualTo("Aurora Platinum");
        assertThat(loaded.limit().value()).isEqualTo(Money.of("12000.00", BRL));
        assertThat(loaded.cycle()).isEqualTo(new BillingCycle(28, 5));
        assertThat(loaded.defaultPaymentAccountId()).get().extracting(AccountId::value).isEqualTo(ana.account());
        assertThat(cards.findById(bob.workspace(), card.id())).isEmpty();
        assertThat(cards.findAllByWorkspace(bob.workspace())).isEmpty();

        CreditCard first = cards.findById(ana.workspace(), card.id()).orElseThrow();
        CreditCard second = cards.findById(ana.workspace(), card.id()).orElseThrow();
        first.archive(NOW);
        cards.save(first);
        second.archive(NOW);
        assertThatThrownBy(() -> cards.save(second)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void databaseRefusesInvalidCardsAndCardTransactions() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                insert into credit_cards (id, workspace_id, nickname, brand, last4, credit_limit, currency, closing_day,
                  due_day, status, created_at, updated_at)
                values (?, ?, 'X', 'VISA', '41111', 10, 'BRL', 28, 5, 'ACTIVE', now(), now())""",
                id, ana.workspace().value())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into credit_cards (id, workspace_id, nickname, brand, last4, credit_limit, currency, closing_day,
                  due_day, status, created_at, updated_at)
                values (?, ?, 'X', 'VISA', '4821', 10, 'BRL', 28, 30, 'ACTIVE', now(), now())""",
                id, ana.workspace().value())).isInstanceOf(DataIntegrityViolationException.class);
        // Conta padrão de outro Workspace: a FK composta recusa mesmo com bug na aplicação.
        assertThatThrownBy(() -> jdbc.update("""
                insert into credit_cards (id, workspace_id, nickname, brand, last4, credit_limit, currency, closing_day,
                  due_day, default_payment_account_id, status, created_at, updated_at)
                values (?, ?, 'X', 'VISA', '4821', 10, 'BRL', 28, 5, ?, 'ACTIVE', now(), now())""",
                id, ana.workspace().value(), bob.account())).isInstanceOf(DataIntegrityViolationException.class);

        CreditCard anas = card(ana, "Ana");
        CreditCard other = card(ana, "Outro da Ana");
        CreditCard bobs = card(bob, "Bob");
        cards.add(anas);
        cards.add(other);
        cards.add(bobs);

        // Exatamente um entre conta e cartão; no cartão só compra e estorno; cartão de outro Workspace.
        assertThatThrownBy(() -> insertTransaction(ana, "EXPENSE", ana.account(), anas.id(), null, "POSTED", "1.00",
                LocalDate.parse("2026-09-01"), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTransaction(ana, "INCOME", null, anas.id(), null, "POSTED", "1.00",
                LocalDate.parse("2026-09-01"), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTransaction(ana, "EXPENSE", null, anas.id(), null, "PENDING", "1.00",
                LocalDate.parse("2026-09-01"), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTransaction(ana, "EXPENSE", null, bobs.id(), null, "POSTED", "1.00",
                LocalDate.parse("2026-09-01"), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTransaction(ana, "TRANSFER", ana.account(), null, bobs.id(), "POSTED", "1.00",
                LocalDate.parse("2026-09-01"), null)).isInstanceOf(DataIntegrityViolationException.class);

        // Parcela de uma compra de outro cartão: a FK (compra, cartão, Workspace) recusa.
        UUID purchase = insertTransaction(ana, "EXPENSE", null, anas.id(), null, "POSTED", "10.00",
                LocalDate.parse("2026-09-01"), null);
        CardInvoice otherInvoice = invoice(other, YearMonth.of(2026, 9));
        assertThatThrownBy(() -> installments.addAll(List.of(new CardInstallment(ana.workspace(), other.id(), purchase,
                1, 1, LocalDate.parse("2026-09-01"), Money.of("10.00", BRL), otherInvoice.id(), "invoice-v1"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invoiceDatesRoundTripAndClosedJobQuery() {
        CreditCard card = card(ana, "Aurora");
        cards.add(card);
        CardInvoice august = invoice(card, YearMonth.of(2026, 8));
        CardInvoice september = invoice(card, YearMonth.of(2026, 9));

        assertThat(invoices.findByCard(ana.workspace(), card.id())).containsExactly(august, september);
        assertThat(invoices.findByCard(bob.workspace(), card.id())).isEmpty();

        List<CardInvoice> pending = transactions.execute(status ->
                invoices.lockPendingClosed(LocalDate.parse("2026-09-22"), 10));
        assertThat(pending).containsExactly(august);

        transactions.executeWithoutResult(status -> invoices.markClosedNotified(august, NOW));
        List<CardInvoice> afterSeptember = transactions.execute(status ->
                invoices.lockPendingClosed(LocalDate.parse("2026-09-28"), 10));
        assertThat(afterSeptember).extracting(CardInvoice::id).containsExactly(september.id());

        invoices.deleteAll(List.of(september));
        assertThat(invoices.findByCard(ana.workspace(), card.id())).extracting(CardInvoice::id)
                .containsExactly(august.id());
    }

    @Test
    void installmentsRoundTripAndMove() {
        CreditCard card = card(ana, "Aurora");
        cards.add(card);
        CardInvoice august = invoice(card, YearMonth.of(2026, 8));
        CardInvoice september = invoice(card, YearMonth.of(2026, 9));
        UUID purchase = insertTransaction(ana, "EXPENSE", null, card.id(), null, "POSTED", "30.00",
                LocalDate.parse("2026-08-10"), null);
        CardInstallment first = new CardInstallment(ana.workspace(), card.id(), purchase, 1, 2,
                LocalDate.parse("2026-08-10"), Money.of("15.00", BRL), august.id(), "invoice-v1");
        CardInstallment second = new CardInstallment(ana.workspace(), card.id(), purchase, 2, 2,
                LocalDate.parse("2026-08-10"), Money.of("15.00", BRL), august.id(), "invoice-v1");
        installments.addAll(List.of(first, second));

        installments.updateInvoices(List.of(second.movedTo(september.id(), "invoice-v1")));

        assertThat(installments.findByTransaction(ana.workspace(), purchase))
                .extracting(CardInstallment::invoiceId).containsExactly(august.id(), september.id());
        assertThat(installments.findByCard(bob.workspace(), card.id())).isEmpty();
    }

    @Test
    void factsSqlMatchesTheInMemoryReference() {
        Random random = new Random(20261022L);
        List<Purchase> purchases = new ArrayList<>();
        List<Credit> credits = new ArrayList<>();
        List<Payment> payments = new ArrayList<>();
        Map<CardId, WorkspaceId> owners = new HashMap<>();

        for (Owner owner : List.of(ana, bob)) {
            for (int c = 0; c < 2; c++) {
                CreditCard card = card(owner, "Cartão " + c);
                cards.add(card);
                owners.put(card.id(), owner.workspace());
                List<Purchase> cardPurchases = randomPurchases(owner, card, random);
                purchases.addAll(cardPurchases);
                for (int k = 0; k < 4; k++) {
                    String status = CARD_STATUSES[random.nextInt(CARD_STATUSES.length)];
                    UUID refundOf = random.nextBoolean() || cardPurchases.isEmpty() ? null
                            : cardPurchases.get(random.nextInt(cardPurchases.size())).transaction();
                    LocalDate date = LocalDate.parse("2026-08-01").plusDays(random.nextInt(60));
                    String amount = BigDecimal.valueOf(1 + random.nextInt(5_000), 2).toPlainString();
                    UUID id = insertTransaction(owner, "REFUND", null, card.id(), null, status, amount, date, refundOf);
                    credits.add(new Credit(card.id(), status, new CreditFact(id, date, Money.of(amount, BRL), refundOf,
                            "Estorno", null, null, "MANUAL")));
                }
                for (int k = 0; k < 4; k++) {
                    String status = ACCOUNT_STATUSES[random.nextInt(ACCOUNT_STATUSES.length)];
                    String amount = BigDecimal.valueOf(1 + random.nextInt(90_000), 2).toPlainString();
                    insertTransaction(owner, "TRANSFER", owner.account(), null, card.id(), status, amount,
                            LocalDate.parse("2026-09-01").plusDays(random.nextInt(20)), null);
                    payments.add(new Payment(card.id(), status, new BigDecimal(amount)));
                }
            }
        }

        for (Owner owner : List.of(ana, bob)) {
            Map<CardId, CardFacts> byCard = reader.factsByCard(owner.workspace());
            List<CardId> ownCards = owners.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(owner.workspace())).map(Map.Entry::getKey).toList();
            assertThat(byCard.keySet()).containsExactlyInAnyOrderElementsOf(ownCards);

            for (CardId card : ownCards) {
                CardFacts facts = reader.facts(owner.workspace(), card);
                assertThat(facts).isEqualTo(byCard.get(card));
                assertThat(facts.installments()).containsExactlyElementsOf(expectedInstallments(purchases, card));
                assertThat(facts.credits()).containsExactlyElementsOf(expectedCredits(credits, card));
                assertThat(facts.payments()).isEqualTo(expectedPayments(payments, card));
            }
            // O cartão do outro Workspace nunca aparece, nem pedido diretamente.
            owners.keySet().stream().filter(card -> !ownCards.contains(card)).forEach(card ->
                    assertThat(reader.facts(owner.workspace(), card).installments()).isEmpty());
        }
    }

    /** Referência: só compras POSTED, na ordem (data da compra, transação, número). */
    private static List<InstallmentFact> expectedInstallments(List<Purchase> purchases, CardId card) {
        return purchases.stream()
                .filter(p -> p.card().equals(card) && p.status().equals("POSTED"))
                .flatMap(p -> p.installments().stream())
                .sorted(Comparator.comparing(InstallmentFact::purchasedOn)
                        .thenComparing(f -> f.transactionId().toString())
                        .thenComparingInt(InstallmentFact::number))
                .toList();
    }

    private static List<CreditFact> expectedCredits(List<Credit> credits, CardId card) {
        return credits.stream()
                .filter(c -> c.card().equals(card) && c.status().equals("POSTED"))
                .map(Credit::fact)
                .sorted(Comparator.comparing(CreditFact::occurredOn).thenComparing(f -> f.transactionId().toString()))
                .toList();
    }

    private static Money expectedPayments(List<Payment> payments, CardId card) {
        return new Money(payments.stream()
                .filter(p -> p.card().equals(card) && p.status().equals("POSTED"))
                .map(Payment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add), BRL);
    }

    private List<Purchase> randomPurchases(Owner owner, CreditCard card, Random random) {
        InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(), List.of());
        Map<YearMonth, CardInvoice> rows = new HashMap<>();
        List<Purchase> purchases = new ArrayList<>();

        for (int p = 0; p < 8; p++) {
            LocalDate purchasedOn = LocalDate.parse("2026-06-01").plusDays(random.nextInt(120));
            int count = 1 + random.nextInt(6);
            String status = CARD_STATUSES[random.nextInt(CARD_STATUSES.length)];
            String description = "Compra";
            BigDecimal amount = BigDecimal.valueOf(100 + random.nextInt(90_000), 2);
            UUID transaction = insertTransaction(owner, "EXPENSE", null, card.id(), null, status,
                    amount.multiply(BigDecimal.valueOf(count)).toPlainString(), purchasedOn, null);

            List<CardInstallment> rowsOfPurchase = new ArrayList<>();
            List<InstallmentFact> facts = new ArrayList<>();
            for (int number = 1; number <= count; number++) {
                YearMonth month = calendar.installmentClosingMonth(purchasedOn, number);
                CardInvoice row = rows.computeIfAbsent(month, m -> invoice(card, m));
                rowsOfPurchase.add(new CardInstallment(owner.workspace(), card.id(), transaction, number, count,
                        purchasedOn, new Money(amount, BRL), row.id(), "invoice-v1"));
                facts.add(new InstallmentFact(transaction, number, count, purchasedOn, new Money(amount, BRL), row.id(),
                        description, null, null, "MANUAL"));
            }
            installments.addAll(rowsOfPurchase);
            purchases.add(new Purchase(card.id(), transaction, status, facts));
        }
        return purchases;
    }

    private CreditCard card(Owner owner, String nickname) {
        return CreditCard.create(CardId.generate(NOW), owner.workspace(), new CardNickname(nickname), null,
                CardBrand.VISA, new CardLast4("4821"), new CardLimit(Money.of("12000.00", BRL)),
                new BillingCycle(28, 5), new AccountId(owner.account()), NOW);
    }

    private CardInvoice invoice(CreditCard card, YearMonth closingMonth) {
        CardInvoice invoice = new CardInvoice(CardInvoiceId.generate(NOW), card.workspaceId(), card.id(),
                new InvoiceCalendar(card.cycle(), List.of()).datesOf(closingMonth), null, null);
        invoices.add(invoice);
        return invoice;
    }

    private UUID insertTransaction(Owner owner, String type, UUID account, CardId card, CardId destinationCard,
                                   String status, String amount, LocalDate occurredOn, UUID refundOf) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into transactions (id, workspace_id, account_id, card_id, destination_card_id, type, amount,
                  currency, occurred_on, description, status, source, refund_of_transaction_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'BRL', ?, ?, ?, 'MANUAL', ?, now(), now())""",
                id, owner.workspace().value(), account, card == null ? null : card.value(),
                destinationCard == null ? null : destinationCard.value(), type, new BigDecimal(amount),
                Date.valueOf(occurredOn), description(type), status, refundOf);
        return id;
    }

    // Descrição fixa por tipo: a das compras é a do fixture ("Compra"), a dos estornos "Estorno".
    private static String description(String type) {
        return switch (type) {
            case "EXPENSE" -> "Compra";
            case "REFUND" -> "Estorno";
            default -> "Pagamento";
        };
    }

    private Owner owner(RequestPostProcessor who) throws Exception {
        String workspace = JsonPath.read(mvc.post().uri("/api/v1/users/me").with(who).exchange().getResponse()
                .getContentAsString(StandardCharsets.UTF_8), "$.workspaceId");
        String account = JsonPath.read(mvc.post().uri("/api/v1/workspaces/{workspaceId}/accounts", workspace)
                .with(who).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Conta", "type": "CHECKING"}
                        """).exchange().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
        return new Owner(new WorkspaceId(UUID.fromString(workspace)), UUID.fromString(account));
    }

    private static RequestPostProcessor identity(String subject, String email) {
        return jwt().jwt(t -> t.subject(subject).claim("iss", ISSUER).claim("email", email).claim("name", "Pessoa"));
    }
}
