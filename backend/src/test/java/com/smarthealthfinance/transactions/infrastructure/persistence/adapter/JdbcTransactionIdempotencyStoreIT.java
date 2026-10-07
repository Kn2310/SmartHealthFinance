package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.application.port.TransactionIdempotencyStore;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTransactionIdempotencyStoreIT extends IntegrationTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("k-1");
    private static final String HASH = "a".repeat(64);

    @Autowired
    TransactionIdempotencyStore store;

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
    private Account aurora;
    private Account bobsAccount;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        anas = persistedWorkspace(activeUser("sub-ana"));
        bobs = persistedWorkspace(activeUser("sub-bob"));
        aurora = account(anas, "Banco Aurora");
        bobsAccount = account(bobs, "Conta do Bob");
        accounts.add(aurora);
        accounts.add(bobsAccount);
    }

    @Test
    void firstClaimWinsAndIsFound() {
        Transaction expense = expense(anas, aurora.id(), "86.40", "Bistrô Lume");

        boolean claimed = claimAndInsert(anas, KEY, HASH, expense);

        assertThat(claimed).isTrue();
        assertThat(store.find(anas, KEY)).contains(new TransactionIdempotencyStore.Entry(HASH, expense.id()));
    }

    @Test
    void secondClaimOfTheSameKeyLosesAndKeepsTheOriginal() {
        Transaction first = expense(anas, aurora.id(), "86.40", "Bistrô Lume");
        claimAndInsert(anas, KEY, HASH, first);

        Boolean claimedAgain = inTransaction(() -> store.claim(anas, KEY, "b".repeat(64),
                TransactionId.generate(CREATED_AT), CREATED_AT));

        assertThat(claimedAgain).isFalse();
        assertThat(store.find(anas, KEY).orElseThrow().transactionId()).isEqualTo(first.id());
        assertThat(store.find(anas, KEY).orElseThrow().requestHash()).isEqualTo(HASH);
    }

    @Test
    void keysAreScopedToTheWorkspace() {
        claimAndInsert(anas, KEY, HASH, expense(anas, aurora.id(), "1", "A"));

        assertThat(store.find(bobs, KEY)).isEmpty();
        assertThat(claimAndInsert(bobs, KEY, HASH, expense(bobs, bobsAccount.id(), "1", "B"))).isTrue();
    }

    /** FK adiada: o claim acontece antes do insert da transação, mas o commit exige que ela exista. */
    @Test
    void claimWithoutItsTransactionCannotCommit() {
        assertThatThrownBy(() -> inTransaction(() -> store.claim(anas, KEY, HASH, TransactionId.generate(CREATED_AT),
                CREATED_AT))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.find(anas, KEY)).isEmpty();
    }

    /** O claim precisa estar na mesma transação do insert do lançamento. */
    @Test
    void claimRequiresAnActiveTransaction() {
        assertThatThrownBy(() -> store.claim(anas, KEY, HASH, TransactionId.generate(CREATED_AT), CREATED_AT))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private boolean claimAndInsert(WorkspaceId workspaceId, IdempotencyKey key, String hash, Transaction transaction) {
        return inTransaction(() -> {
            boolean claimed = store.claim(workspaceId, key, hash, transaction.id(), CREATED_AT);
            transactions.add(transaction);
            return claimed;
        });
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
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
}
