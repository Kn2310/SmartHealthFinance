package com.smarthealthfinance.identity.infrastructure.persistence;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserRepository;
import com.smarthealthfinance.identity.domain.Workspace;
import com.smarthealthfinance.identity.domain.WorkspaceId;
import com.smarthealthfinance.identity.domain.WorkspaceRepository;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JpaWorkspaceRepositoryIT extends IntegrationTest {

	@Autowired
	WorkspaceRepository workspaces;

	@Autowired
	UserRepository users;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	private TransactionTemplate tx;

	private User ana;

	private User bob;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		IdentityTables.clean(jdbc);
		ana = persisted(activeUser("sub-ana"));
		bob = persisted(activeUser("sub-bob"));
	}

	@Test
	void addPersonalIfAbsentPersistsWorkspaceAndMemberships() {
		Workspace workspace = personalWorkspace(ana);

		assertThat(inTx(() -> workspaces.addPersonalIfAbsent(workspace))).isTrue();

		Workspace loaded = workspaces.findById(workspace.id()).orElseThrow();
		assertThat(loaded).usingRecursiveComparison().isEqualTo(workspace);
	}

	@Test
	void addPersonalIfAbsentIsIdempotentPerOwner() {
		Workspace first = personalWorkspace(ana);

		assertThat(inTx(() -> workspaces.addPersonalIfAbsent(first))).isTrue();
		assertThat(inTx(() -> workspaces.addPersonalIfAbsent(personalWorkspace(ana)))).isFalse();

		assertThat(count("workspaces")).isEqualTo(1);
		assertThat(count("workspace_memberships")).isEqualTo(1);
		assertThat(workspaces.findPersonalByOwner(ana.id()).orElseThrow().id()).isEqualTo(first.id());
	}

	@Test
	void concurrentCreationForSameOwnerInsertsExactlyOnce() throws Exception {
		int attempts = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
			for (int i = 0; i < attempts; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return inTx(() -> workspaces.addPersonalIfAbsent(personalWorkspace(ana)));
				}));
			}
			start.countDown();

			int inserted = 0;
			for (Future<Boolean> result : results) {
				if (result.get(10, TimeUnit.SECONDS)) {
					inserted++;
				}
			}
			assertThat(inserted).isEqualTo(1);
		}
		assertThat(count("workspaces")).isEqualTo(1);
		assertThat(count("workspace_memberships")).isEqualTo(1);
	}

	@Test
	void addPersonalIfAbsentRequiresCallerTransaction() {
		assertThatThrownBy(() -> workspaces.addPersonalIfAbsent(personalWorkspace(ana)))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(count("workspaces")).isZero();
	}

	@Test
	void findAllByMemberReturnsOnlyWorkspacesWithMembership() {
		Workspace anas = personalWorkspace(ana);
		Workspace bobs = personalWorkspace(bob);
		inTx(() -> workspaces.addPersonalIfAbsent(anas));
		inTx(() -> workspaces.addPersonalIfAbsent(bobs));

		assertThat(workspaces.findAllByMember(ana.id())).extracting(Workspace::id).containsExactly(anas.id());
		assertThat(workspaces.findAllByMember(bob.id())).extracting(Workspace::id).containsExactly(bobs.id());
	}

	@Test
	void findAllByMemberLoadsTheCompleteMembershipList() {
		Workspace anas = personalWorkspace(ana);
		inTx(() -> workspaces.addPersonalIfAbsent(anas));

		assertThat(workspaces.findAllByMember(ana.id()).getFirst().memberships()).hasSize(1);
	}

	@Test
	void lookupsReturnEmptyForUnknownData() {
		assertThat(workspaces.findById(WorkspaceId.generate(CREATED_AT))).isEmpty();
		assertThat(workspaces.findPersonalByOwner(ana.id())).isEmpty();
		assertThat(workspaces.findAllByMember(ana.id())).isEmpty();
	}

	// --- garantias do banco (independentes da aplicação) ---

	@Test
	void databaseAllowsAtMostOnePersonalWorkspacePerOwner() {
		insertWorkspaceRow(UUID.randomUUID(), ana, "BRL");

		assertThatThrownBy(() -> insertWorkspaceRow(UUID.randomUUID(), ana, "BRL"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsDuplicateMembership() {
		UUID workspaceId = UUID.randomUUID();
		insertWorkspaceRow(workspaceId, ana, "BRL");
		insertMembershipRow(workspaceId, ana);

		assertThatThrownBy(() -> insertMembershipRow(workspaceId, ana))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsNonBrlBaseCurrency() {
		assertThatThrownBy(() -> insertWorkspaceRow(UUID.randomUUID(), ana, "USD"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsWorkspaceForUnknownOwner() {
		assertThatThrownBy(() -> jdbc.update("""
				insert into workspaces (id, kind, owner_user_id, name, base_currency, created_at, updated_at)
				values (?, 'PERSONAL', ?, 'Pessoal', 'BRL', now(), now())
				""", UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}

	private User persisted(User user) {
		inTx(() -> users.addIfAbsent(user));
		return user;
	}

	private void insertWorkspaceRow(UUID id, User owner, String currency) {
		jdbc.update("""
				insert into workspaces (id, kind, owner_user_id, name, base_currency, created_at, updated_at)
				values (?, 'PERSONAL', ?, 'Pessoal', ?, now(), now())
				""", id, owner.id().value(), currency);
	}

	private void insertMembershipRow(UUID workspaceId, User user) {
		jdbc.update("""
				insert into workspace_memberships (workspace_id, user_id, role, joined_at)
				values (?, ?, 'OWNER', now())
				""", workspaceId, user.id().value());
	}

	private <T> T inTx(Supplier<T> action) {
		return tx.execute(status -> action.get());
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

}
