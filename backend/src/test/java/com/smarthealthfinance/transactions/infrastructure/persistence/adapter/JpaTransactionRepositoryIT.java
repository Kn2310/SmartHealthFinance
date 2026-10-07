package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionPage;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.brl;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.openingBalance;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.refund;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JpaTransactionRepositoryIT extends IntegrationTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);

    @Autowired
    TransactionRepository transactions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    UserRepository users;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private WorkspaceId anas;
    private WorkspaceId bobs;
    private AccountId aurora;
    private AccountId norte;
    private AccountId bobsAccount;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        anas = persistedWorkspace(activeUser("sub-ana"));
        bobs = persistedWorkspace(activeUser("sub-bob"));
        aurora = persistedAccount(anas, "Banco Aurora");
        norte = persistedAccount(anas, "Banco Norte");
        bobsAccount = persistedAccount(bobs, "Conta do Bob");
    }

    // --- round trip ---

    @Test
    void addThenFindByIdRoundTrips() {
        Transaction expense = expense(anas, aurora, "86.40", "Bistrô Lume");

        transactions.add(expense);

        assertThat(transactions.findById(anas, expense.id()).orElseThrow()).usingRecursiveComparison()
                .isEqualTo(expense);
    }

    @Test
    void typeSpecificFieldsRoundTrip() {
        Transaction move = transfer(anas, aurora, norte, "500");
        Transaction opening = openingBalance(anas, norte, AdjustmentDirection.DECREASE, "350");
        Transaction purchase = expense(anas, aurora, "100", "Loja");
        Transaction refund = refund(anas, aurora, "30", purchase);
        List.of(move, opening, purchase, refund).forEach(transactions::add);

        assertThat(transactions.findById(anas, move.id()).orElseThrow().destinationAccountId()).contains(norte);
        assertThat(transactions.findById(anas, opening.id()).orElseThrow().adjustmentDirection())
                .contains(AdjustmentDirection.DECREASE);
        assertThat(transactions.findById(anas, refund.id()).orElseThrow().refundOfTransactionId())
                .contains(purchase.id());
    }

    /** NUMERIC(19,4): sem perda de precisão no maior valor aceito pelo domínio. */
    @Test
    void storesMoneyExactly() {
        Transaction large = expense(anas, aurora, "999999999999999.99", "Grande");
        transactions.add(large);

        assertThat(jdbc.queryForObject("select amount from transactions where id = ?", BigDecimal.class,
                large.id().value())).isEqualByComparingTo("999999999999999.99");
        assertThat(transactions.findById(anas, large.id()).orElseThrow().amount()).isEqualTo(large.amount());
    }

    @Test
    void findByIdIsScopedToTheWorkspace() {
        Transaction expense = expense(anas, aurora, "86.40", "Bistrô Lume");
        transactions.add(expense);

        assertThat(transactions.findById(bobs, expense.id())).isEmpty();
        assertThat(inTransaction(() -> transactions.findByIdForUpdate(bobs, expense.id()))).isEmpty();
        assertThat(inTransaction(() -> transactions.findByIdForUpdate(anas, expense.id()))).isPresent();
    }

    @Test
    void findsRefundsOfAnOriginal() {
        Transaction purchase = expense(anas, aurora, "100", "Loja");
        Transaction other = expense(anas, aurora, "50", "Outra loja");
        transactions.add(purchase);
        transactions.add(other);
        transactions.add(refund(anas, aurora, "30", purchase));
        transactions.add(refund(anas, norte, "20", purchase));
        transactions.add(refund(anas, aurora, "10", other));

        assertThat(transactions.findRefundsOf(anas, purchase.id())).extracting(t -> t.amount().toPlainString())
                .containsExactlyInAnyOrder("30.00", "20.00");
        assertThat(transactions.findRefundsOf(bobs, purchase.id())).isEmpty();
    }

    // --- busca ---

    @Test
    void searchOrdersNewestFirstAndPaginates() {
        for (int day = 1; day <= 5; day++) {
            transactions.add(expense(anas, aurora, "10", LocalDate.of(2026, 9, day), "Dia " + day, CREATED_AT));
        }
        transactions.add(expense(anas, aurora, "10", LocalDate.of(2026, 9, 5), "Dia 5 depois", LATER));
        transactions.add(expense(bobs, bobsAccount, "10", LocalDate.of(2026, 9, 9), "Do Bob", CREATED_AT));

        TransactionPage first = transactions.search(anas, TransactionCriteria.none(), 0, 3);
        TransactionPage second = transactions.search(anas, TransactionCriteria.none(), 1, 3);

        assertThat(descriptions(first)).containsExactly("Dia 5 depois", "Dia 5", "Dia 4");
        assertThat(descriptions(second)).containsExactly("Dia 3", "Dia 2", "Dia 1");
        assertThat(first.totalItems()).isEqualTo(6);
    }

    @Test
    void searchFiltersByEveryCriterion() {
        transactions.add(expense(anas, aurora, "86.40", LocalDate.of(2026, 9, 22), "Bistrô Lume", CREATED_AT));
        transactions.add(pendingExpense(anas, aurora, "39.90", "Streamo"));
        transactions.add(income(anas, aurora, "300", LocalDate.of(2026, 9, 18), "Estúdio Ponto — freelance"));
        transactions.add(transfer(anas, aurora, norte, "500"));

        assertThat(descriptions(search(new TransactionCriteria(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18),
                null, null, null, null)))).containsExactly("Estúdio Ponto — freelance");
        assertThat(descriptions(search(new TransactionCriteria(null, null, TransactionType.EXPENSE,
                TransactionStatus.PENDING, null, null)))).containsExactly("Streamo");
        assertThat(descriptions(search(new TransactionCriteria(null, null, null, null, norte, null))))
                .containsExactly("Transferência para Reserva");
        assertThat(descriptions(search(new TransactionCriteria(null, null, null, null, null, "LUME"))))
                .containsExactly("Bistrô Lume");
    }

    /** Busca textual é literal: curingas do LIKE no texto do usuário não ampliam o resultado. */
    @Test
    void searchTextEscapesLikeWildcards() {
        transactions.add(expense(anas, aurora, "10", "Desconto 100% Loja"));
        transactions.add(expense(anas, aurora, "10", "Desconto 1000 Loja"));
        transactions.add(expense(anas, aurora, "10", "a_b"));
        transactions.add(expense(anas, aurora, "10", "axb"));

        assertThat(descriptions(search(new TransactionCriteria(null, null, null, null, null, "100%"))))
                .containsExactly("Desconto 100% Loja");
        assertThat(descriptions(search(new TransactionCriteria(null, null, null, null, null, "a_b"))))
                .containsExactly("a_b");
    }

    // --- atualização ---

    @Test
    void savePersistsChangesAndIncrementsVersion() {
        Transaction pending = pendingExpense(anas, aurora, "39.90", "Streamo");
        transactions.add(pending);

        Transaction loaded = transactions.findById(anas, pending.id()).orElseThrow();
        loaded.post(LATER);
        loaded.updateDescription(new TransactionDescription("Streamo Premium"), LATER);
        transactions.save(loaded);

        Transaction reloaded = transactions.findById(anas, pending.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(reloaded.description().value()).isEqualTo("Streamo Premium");
        assertThat(reloaded.updatedAt()).isEqualTo(LATER);
        assertThat(reloaded.version()).isEqualTo(1);
    }

    @Test
    void saveWithStaleVersionFails() {
        Transaction pending = pendingExpense(anas, aurora, "39.90", "Streamo");
        transactions.add(pending);
        Transaction first = transactions.findById(anas, pending.id()).orElseThrow();
        Transaction second = transactions.findById(anas, pending.id()).orElseThrow();

        first.cancel(LATER);
        transactions.save(first);
        second.post(LATER);

        assertThatThrownBy(() -> transactions.save(second)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(transactions.findById(anas, pending.id()).orElseThrow().status())
                .isEqualTo(TransactionStatus.CANCELLED);
    }

    /** Campos financeiros nunca são alterados por save (colunas updatable = false). */
    @Test
    void financialFactsAreImmutableInTheDatabaseMapping() {
        Transaction expense = expense(anas, aurora, "86.40", "Bistrô Lume");
        transactions.add(expense);
        Transaction tampered = Transaction.restore(expense.id(), anas, TransactionType.INCOME, norte, null, null,
                brl("1"), SEP_22.plusDays(1),
                expense.description(), expense.status(), expense.source(), null, expense.createdAt(), LATER, 0);

        transactions.save(tampered);

        Transaction reloaded = transactions.findById(anas, expense.id()).orElseThrow();
        assertThat(reloaded.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(reloaded.accountId()).isEqualTo(aurora);
        assertThat(reloaded.amount()).isEqualTo(expense.amount());
        assertThat(reloaded.occurredOn()).isEqualTo(SEP_22);
    }

    // --- garantias do banco (independentes da aplicação) ---

    @Test
    void databaseRejectsAccountOfAnotherWorkspace() {
        assertThatThrownBy(() -> insertRow(anas.value(), bobsAccount.value(), null, "EXPENSE", null, "10", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), bobsAccount.value(), "TRANSFER", null, "10",
                null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsRefundOfAnotherWorkspaceTransaction() {
        Transaction bobsPurchase = expense(bobs, bobsAccount, "100", "Loja");
        transactions.add(bobsPurchase);

        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "REFUND", null, "10",
                bobsPurchase.id().value())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "EXPENSE", null, "0", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "EXPENSE", null, "-1", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesTypeSpecificColumns() {
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "TRANSFER", null, "10", null))
                .as("transferência sem destino").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), aurora.value(), "TRANSFER", null, "10", null))
                .as("transferência para a própria conta").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), norte.value(), "EXPENSE", null, "10", null))
                .as("destino fora de transferência").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "ADJUSTMENT", null, "10", null))
                .as("ajuste sem direção").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "EXPENSE", "INCREASE", "10", null))
                .as("direção fora de ajuste").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsRefundLinkOnNonRefund() {
        Transaction purchase = expense(anas, aurora, "100", "Loja");
        transactions.add(purchase);

        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "EXPENSE", null, "10",
                purchase.id().value())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownTypeStatusAndCurrency() {
        assertThatThrownBy(() -> insertRow(anas.value(), aurora.value(), null, "PAYMENT", null, "10", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT_SQL, UUID.randomUUID(), anas.value(), aurora.value(), null,
                "EXPENSE", null, new BigDecimal("10"), "USD", "POSTED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT_SQL, UUID.randomUUID(), anas.value(), aurora.value(), null,
                "EXPENSE", null, new BigDecimal("10"), "BRL", "DELETED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Sem cascade: transações nunca somem junto com a conta ou o Workspace. */
    @Test
    void accountAndWorkspaceWithTransactionsCannotBeDeleted() {
        transactions.add(expense(anas, aurora, "86.40", "Bistrô Lume"));

        assertThatThrownBy(() -> jdbc.update("delete from accounts where id = ?", aurora.value()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from workspaces where id = ?", anas.value()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers ---

    private static final String INSERT_SQL = """
            insert into transactions (id, workspace_id, account_id, destination_account_id, type, adjustment_direction,
                                      amount, currency, occurred_on, description, status, source,
                                      refund_of_transaction_id, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, date '2026-09-22', 'Linha', ?, 'MANUAL', ?, now(), now())
            """;

    private void insertRow(UUID workspaceId, UUID accountId, UUID destinationId, String type, String direction,
                           String amount, UUID refundOf) {
        jdbc.update(INSERT_SQL, UUID.randomUUID(), workspaceId, accountId, destinationId, type, direction,
                new BigDecimal(amount), "BRL", "POSTED", refundOf);
    }

    private TransactionPage search(TransactionCriteria criteria) {
        return transactions.search(anas, criteria, 0, 100);
    }

    private static List<String> descriptions(TransactionPage page) {
        return page.items().stream().map(t -> t.description().value()).toList();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private WorkspaceId persistedWorkspace(User owner) {
        Workspace workspace = personalWorkspace(owner);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            users.addIfAbsent(owner);
            workspaces.addPersonalIfAbsent(workspace);
        });
        return workspace.id();
    }

    private AccountId persistedAccount(WorkspaceId workspaceId, String name) {
        Account account = account(workspaceId, name);
        accounts.add(account);
        return account.id();
    }
}
