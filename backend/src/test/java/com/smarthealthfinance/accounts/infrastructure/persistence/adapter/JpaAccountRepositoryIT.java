package com.smarthealthfinance.accounts.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JpaAccountRepositoryIT extends IntegrationTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);

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

    private WorkspaceId anasWorkspace;

    private WorkspaceId bobsWorkspace;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        anasWorkspace = persistedWorkspace(activeUser("sub-ana"));
        bobsWorkspace = persistedWorkspace(activeUser("sub-bob"));
    }

    @Test
    void addThenFindByIdRoundTrips() {
        Account account = account(anasWorkspace, "Banco Aurora");

        accounts.add(account);

        assertThat(accounts.findById(anasWorkspace, account.id()).orElseThrow()).usingRecursiveComparison()
                .isEqualTo(account);
    }

    @Test
    void persistsAccountWithoutInstitution() {
        Account account = Account.create(AccountId.generate(CREATED_AT), anasWorkspace, new AccountName("Carteira"),
                AccountType.OTHER, null, Workspace.DEFAULT_BASE_CURRENCY, false, CREATED_AT);

        accounts.add(account);

        Account loaded = accounts.findById(anasWorkspace, account.id()).orElseThrow();
        assertThat(loaded.institutionName()).isEmpty();
        assertThat(loaded.includedInTotal()).isFalse();
    }

    @Test
    void findByIdIsScopedToTheWorkspace() {
        Account account = account(anasWorkspace, "Banco Aurora");
        accounts.add(account);

        assertThat(accounts.findById(bobsWorkspace, account.id())).isEmpty();
    }

    @Test
    void findAllByWorkspaceReturnsOnlyThatWorkspaceInCreationOrder() {
        accounts.add(account(anasWorkspace, "Segunda", CREATED_AT.plusSeconds(10)));
        accounts.add(account(anasWorkspace, "Primeira", CREATED_AT));
        accounts.add(account(bobsWorkspace, "Conta do Bob", CREATED_AT));

        assertThat(accounts.findAllByWorkspace(anasWorkspace)).extracting(a -> a.name().value())
                .containsExactly("Primeira", "Segunda");
    }

    @Test
    void savePersistsChangesAndIncrementsVersion() {
        Account account = account(anasWorkspace, "Banco Aurora");
        accounts.add(account);

        Account loaded = accounts.findById(anasWorkspace, account.id()).orElseThrow();
        loaded.updateDetails(new AccountName("Reserva"), AccountType.SAVINGS, new InstitutionName("Banco Norte"),
                false, LATER);
        accounts.save(loaded);

        Account reloaded = accounts.findById(anasWorkspace, account.id()).orElseThrow();
        assertThat(reloaded.name()).isEqualTo(new AccountName("Reserva"));
        assertThat(reloaded.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(reloaded.updatedAt()).isEqualTo(LATER);
        assertThat(reloaded.version()).isEqualTo(1);
    }

    @Test
    void saveWithStaleVersionFails() {
        Account account = account(anasWorkspace, "Banco Aurora");
        accounts.add(account);
        Account first = accounts.findById(anasWorkspace, account.id()).orElseThrow();
        Account second = accounts.findById(anasWorkspace, account.id()).orElseThrow();

        first.archive(LATER);
        accounts.save(first);
        second.updateDetails(new AccountName("Outro nome"), AccountType.OTHER, null, false, LATER);

        assertThatThrownBy(() -> accounts.save(second)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(accounts.findById(anasWorkspace, account.id()).orElseThrow().status())
                .isEqualTo(AccountStatus.ARCHIVED);
    }

    @Test
    void lookupsReturnEmptyForUnknownData() {
        assertThat(accounts.findById(anasWorkspace, AccountId.generate(CREATED_AT))).isEmpty();
        assertThat(accounts.findAllByWorkspace(anasWorkspace)).isEmpty();
    }

    // --- garantias do banco (independentes da aplicação) ---

    @Test
    void databaseRejectsAccountForUnknownWorkspace() {
        assertThatThrownBy(() -> insertAccountRow(UUID.randomUUID(), "Conta", "CHECKING", "BRL", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownType() {
        assertThatThrownBy(() -> insertAccountRow(anasWorkspace.value(), "Conta", "CREDIT_CARD", "BRL", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownStatus() {
        assertThatThrownBy(() -> insertAccountRow(anasWorkspace.value(), "Conta", "CHECKING", "BRL", "DELETED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonBrlCurrency() {
        assertThatThrownBy(() -> insertAccountRow(anasWorkspace.value(), "Conta", "CHECKING", "USD", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsBlankName() {
        assertThatThrownBy(() -> insertAccountRow(anasWorkspace.value(), "   ", "CHECKING", "BRL", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void workspaceWithAccountsCannotBeDeleted() {
        accounts.add(account(anasWorkspace, "Banco Aurora"));

        assertThatThrownBy(() -> jdbc.update("delete from workspaces where id = ?", anasWorkspace.value()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private WorkspaceId persistedWorkspace(User owner) {
        Workspace workspace = personalWorkspace(owner);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            users.addIfAbsent(owner);
            workspaces.addPersonalIfAbsent(workspace);
        });
        return workspace.id();
    }

    private void insertAccountRow(UUID workspaceId, String name, String type, String currency, String status) {
        jdbc.update("""
                insert into accounts (id, workspace_id, name, type, currency, included_in_total, status,
                                      created_at, updated_at)
                values (?, ?, ?, ?, ?, true, ?, now(), now())
                """, UUID.randomUUID(), workspaceId, name, type, currency, status);
    }
}
