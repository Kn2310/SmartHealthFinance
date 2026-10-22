package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CardTotals;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CommittedInstallment;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InvoiceItem;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
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
import java.sql.Timestamp;
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
 * Persistência de Cartões e paridade do SQL de fatura (ADR-0026 §7): as somas de {@link JdbcCardLedgerReader} são
 * comparadas com uma referência em memória sobre os mesmos fatos, com todos os status de transação, parcelas
 * canceladas e dois Workspaces.
 */
class CardPersistenceIT extends IntegrationTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    private static final String[] STATUSES = {"POSTED", "POSTED", "POSTED", "PENDING", "CANCELLED", "REVERSED"};

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CreditCardRepository cards;

    @Autowired
    CardInvoiceRepository invoices;

    @Autowired
    CardLedgerReader reader;

    @Autowired
    TransactionTemplate transactions;

    private Owner ana;
    private Owner bob;

    record Owner(WorkspaceId workspace, UUID account) {}

    /** Uma parcela do fixture, com o status da transação dona. */
    record Installment(CardId card, CardInvoiceId invoice, UUID transaction, int number, int count, LocalDate purchasedOn,
                       BigDecimal amount, BigDecimal cancelled, String status, String description) {}

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
        assertThat(loaded.defaultPaymentAccountId()).get().extracting(id -> id.value()).isEqualTo(ana.account());
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
    void databaseRefusesInvalidCardsAndCrossWorkspaceReferences() {
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
        cards.add(anas);
        cards.add(other);
        CardInvoice anasInvoice = invoice(anas, YearMonth.of(2026, 9));
        UUID transaction = transaction(ana, "POSTED", "10.00", LocalDate.parse("2026-09-01"), "Compra");
        // Parcela de um cartão apontando para a fatura de outro: a FK (invoice, card, workspace) recusa.
        assertThatThrownBy(() -> insertInstallment(new Installment(other.id(), anasInvoice.id(), transaction, 1, 1,
                LocalDate.parse("2026-09-01"), new BigDecimal("10.00"), BigDecimal.ZERO, "POSTED", "Compra"),
                ana.workspace())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInstallment(new Installment(anas.id(), anasInvoice.id(), transaction, 1, 1,
                LocalDate.parse("2026-09-01"), new BigDecimal("10.00"), new BigDecimal("10.01"), "POSTED", "Compra"),
                ana.workspace())).isInstanceOf(DataIntegrityViolationException.class);
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
    }

    @Test
    void ledgerSqlMatchesTheInMemoryReference() {
        Random random = new Random(20261022L);
        List<Installment> fixture = new ArrayList<>();
        Map<CardId, WorkspaceId> owners = new HashMap<>();
        for (Owner owner : List.of(ana, bob)) {
            for (int c = 0; c < 2; c++) {
                CreditCard card = card(owner, "Cartão " + c);
                cards.add(card);
                owners.put(card.id(), owner.workspace());
                fixture.addAll(randomPurchases(owner, card, random));
            }
        }
        fixture.forEach(installment -> insertInstallment(installment, owners.get(installment.card())));

        for (Owner owner : List.of(ana, bob)) {
            Map<CardId, CardTotals> byCard = reader.totalsByCard(owner.workspace());
            Map<CardId, Map<CardInvoiceId, BigDecimal>> expected = reference(fixture, owners, owner.workspace());

            assertThat(byCard.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((card, perInvoice) -> {
                CardTotals totals = reader.totals(owner.workspace(), card);
                assertThat(totals).isEqualTo(byCard.get(card));
                assertThat(totals.payments()).isEqualTo(Money.zero(BRL));
                Map<CardInvoiceId, BigDecimal> actual = new HashMap<>();
                totals.invoices().forEach((invoice, sums) -> {
                    actual.put(invoice, sums.charges().amount());
                    assertThat(sums.credits()).isEqualTo(Money.zero(BRL));
                });
                assertThat(actual).isEqualTo(perInvoice);

                perInvoice.keySet().forEach(invoice -> assertItems(fixture, owner.workspace(), card, invoice));
                assertCommitted(fixture, owner.workspace(), card, perInvoice.keySet().stream().toList());
            });
        }
    }

    private void assertItems(List<Installment> fixture, WorkspaceId workspace, CardId card, CardInvoiceId invoice) {
        List<Installment> expected = fixture.stream()
                .filter(i -> i.card().equals(card) && i.invoice().equals(invoice) && i.status().equals("POSTED"))
                .sorted(Comparator.comparing(Installment::purchasedOn).reversed()
                        .thenComparing(Installment::number).thenComparing(i -> i.transaction().toString()))
                .toList();

        assertThat(reader.itemCount(workspace, card, invoice)).isEqualTo(expected.size());
        List<InvoiceItem> page = reader.items(workspace, card, invoice, 0, 100).items();
        assertThat(page).extracting(InvoiceItem::transactionId)
                .containsExactlyElementsOf(expected.stream().map(Installment::transaction).toList());
        assertThat(page).extracting(item -> item.amount().amount())
                .containsExactlyElementsOf(expected.stream().map(i -> i.amount().setScale(4)).toList());
    }

    private void assertCommitted(List<Installment> fixture, WorkspaceId workspace, CardId card,
                                 List<CardInvoiceId> invoiceIds) {
        List<CommittedInstallment> committed = reader.installments(workspace, card, invoiceIds);
        long expected = fixture.stream()
                .filter(i -> i.card().equals(card) && i.status().equals("POSTED")
                        && i.amount().compareTo(i.cancelled()) > 0)
                .count();
        assertThat(committed).hasSize((int) expected);
        assertThat(reader.installments(workspace, card, List.of())).isEmpty();
    }

    /** Referência: só POSTED, soma de (valor − cancelado) por fatura, só os cartões do Workspace. */
    private static Map<CardId, Map<CardInvoiceId, BigDecimal>> reference(List<Installment> fixture,
                                                                     Map<CardId, WorkspaceId> owners,
                                                                     WorkspaceId workspace) {
        Map<CardId, Map<CardInvoiceId, BigDecimal>> expected = new HashMap<>();
        for (Installment installment : fixture) {
            if (!owners.get(installment.card()).equals(workspace) || !installment.status().equals("POSTED")) {
                continue;
            }
            expected.computeIfAbsent(installment.card(), ignored -> new HashMap<>())
                    .merge(installment.invoice(), installment.amount().subtract(installment.cancelled()).setScale(4),
                            BigDecimal::add);
        }
        return expected;
    }

    private List<Installment> randomPurchases(Owner owner, CreditCard card, Random random) {
        InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(), List.of());
        Map<YearMonth, CardInvoice> rows = new HashMap<>();
        List<Installment> installments = new ArrayList<>();

        for (int p = 0; p < 12; p++) {
            LocalDate purchasedOn = LocalDate.parse("2026-06-01").plusDays(random.nextInt(120));
            int count = 1 + random.nextInt(6);
            String status = STATUSES[random.nextInt(STATUSES.length)];
            String description = "Compra " + p;
            BigDecimal amount = BigDecimal.valueOf(100 + random.nextInt(90_000), 2);
            UUID transaction = transaction(owner, status, amount.multiply(BigDecimal.valueOf(count)).toPlainString(),
                    purchasedOn, description);

            for (int number = 1; number <= count; number++) {
                YearMonth month = calendar.installmentClosingMonth(purchasedOn, number);
                CardInvoice row = rows.computeIfAbsent(month, m -> invoice(card, m));
                BigDecimal cancelled = random.nextInt(4) == 0 ? amount : random.nextInt(4) == 0
                        ? amount.divide(BigDecimal.TWO, 2, java.math.RoundingMode.DOWN) : BigDecimal.ZERO;
                installments.add(new Installment(card.id(), row.id(), transaction, number, count, purchasedOn, amount,
                        cancelled, status, description));
            }
        }
        return installments;
    }

    private CreditCard card(Owner owner, String nickname) {
        return CreditCard.create(CardId.generate(NOW), owner.workspace(), new CardNickname(nickname), null,
                CardBrand.VISA, new CardLast4("4821"), new CardLimit(Money.of("12000.00", BRL)),
                new BillingCycle(28, 5), new com.smarthealthfinance.accounts.domain.valueobject.AccountId(owner.account()),
                NOW);
    }

    private CardInvoice invoice(CreditCard card, YearMonth closingMonth) {
        CardInvoice invoice = new CardInvoice(CardInvoiceId.generate(NOW), card.workspaceId(), card.id(),
                new InvoiceCalendar(card.cycle(), List.of()).datesOf(closingMonth), null, null);
        invoices.add(invoice);
        return invoice;
    }

    private UUID transaction(Owner owner, String status, String amount, LocalDate occurredOn, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into transactions (id, workspace_id, account_id, type, amount, currency, occurred_on,
                  description, status, source, created_at, updated_at)
                values (?, ?, ?, 'EXPENSE', ?, 'BRL', ?, ?, ?, 'MANUAL', now(), now())""",
                id, owner.workspace().value(), owner.account(), new BigDecimal(amount), Date.valueOf(occurredOn),
                description, status);
        return id;
    }

    private void insertInstallment(Installment installment, WorkspaceId workspace) {
        jdbc.update("""
                insert into card_installments (transaction_id, number, workspace_id, card_id, installment_count,
                  purchased_on, amount, cancelled_amount, currency, invoice_id, rules_version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'BRL', ?, 'invoice-v1', ?, ?)""",
                installment.transaction(), installment.number(), workspace.value(), installment.card().value(),
                installment.count(), Date.valueOf(installment.purchasedOn()), installment.amount(),
                installment.cancelled(), installment.invoice().value(), Timestamp.from(NOW), Timestamp.from(NOW));
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
