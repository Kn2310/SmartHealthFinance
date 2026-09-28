package com.smarthealthfinance.identity.infrastructure.persistence;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.externalIdentity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.smarthealthfinance.identity.domain.DisplayName;
import com.smarthealthfinance.identity.domain.Email;
import com.smarthealthfinance.identity.domain.ExternalIdentity;
import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.UserRepository;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O insertIfAbsent é @Modifying e roda, em produção, dentro da transação do caso de uso;
 * aqui a transação é aberta explicitamente com TransactionTemplate.
 */
class JpaUserRepositoryIT extends IntegrationTest {

	private static final Instant LATER = CREATED_AT.plus(Duration.ofDays(1));

	@Autowired
	UserRepository users;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	private TransactionTemplate tx;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		jdbc.update("delete from users");
	}

	@Test
	void addIfAbsentPersistsAllFields() {
		User user = activeUser("sub-ana", "ana@example.com", "Ana Silva");

		assertThat(inTx(() -> users.addIfAbsent(user))).isTrue();

		User loaded = users.findById(user.id()).orElseThrow();
		assertThat(loaded).usingRecursiveComparison().isEqualTo(user);
	}

	@Test
	void addIfAbsentIsIdempotentPerExternalIdentity() {
		User first = activeUser("sub-ana");
		User duplicate = activeUser("sub-ana");

		assertThat(inTx(() -> users.addIfAbsent(first))).isTrue();
		assertThat(inTx(() -> users.addIfAbsent(duplicate))).isFalse();

		assertThat(countUsers()).isEqualTo(1);
		assertThat(users.findByExternalIdentity(externalIdentity("sub-ana")).orElseThrow().id()).isEqualTo(first.id());
	}

	@Test
	void concurrentProvisioningOfSameIdentityInsertsExactlyOnce() throws Exception {
		int attempts = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
			for (int i = 0; i < attempts; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return inTx(() -> users.addIfAbsent(activeUser("sub-race")));
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
		assertThat(countUsers()).isEqualTo(1);
	}

	@Test
	void findByExternalIdentityDistinguishesIssuers() {
		inTx(() -> users.addIfAbsent(activeUser("sub-ana")));

		assertThat(users.findByExternalIdentity(externalIdentity("sub-ana"))).isPresent();
		assertThat(users.findByExternalIdentity(new ExternalIdentity("https://other.idp", "sub-ana"))).isEmpty();
		assertThat(users.findByExternalIdentity(externalIdentity("sub-bob"))).isEmpty();
	}

	@Test
	void findByIdReturnsEmptyForUnknownUser() {
		assertThat(users.findById(new UserId(UUID.randomUUID()))).isEmpty();
	}

	@Test
	void saveUpdatesMutableFieldsAndIncrementsVersion() {
		User user = activeUser("sub-ana", "ana@example.com", "Ana Silva");
		inTx(() -> users.addIfAbsent(user));

		User loaded = users.findById(user.id()).orElseThrow();
		loaded.rename(new DisplayName("Ana Souza"), LATER);
		loaded.syncEmail(new Email("ana.souza@example.com"), LATER);
		users.save(loaded);

		User reloaded = users.findById(user.id()).orElseThrow();
		assertThat(reloaded.displayName()).isEqualTo(new DisplayName("Ana Souza"));
		assertThat(reloaded.email()).isEqualTo(new Email("ana.souza@example.com"));
		assertThat(reloaded.updatedAt()).isEqualTo(LATER);
		assertThat(reloaded.createdAt()).isEqualTo(CREATED_AT);
		assertThat(reloaded.version()).isEqualTo(1);
	}

	@Test
	void saveWithStaleVersionFailsWithOptimisticLock() {
		User user = activeUser("sub-ana");
		inTx(() -> users.addIfAbsent(user));

		User first = users.findById(user.id()).orElseThrow();
		User stale = users.findById(user.id()).orElseThrow();

		first.rename(new DisplayName("Primeiro"), LATER);
		users.save(first);

		stale.rename(new DisplayName("Obsoleto"), LATER);
		assertThatThrownBy(() -> users.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(users.findById(user.id()).orElseThrow().displayName()).isEqualTo(new DisplayName("Primeiro"));
	}

	@Test
	void databaseRejectsUnknownStatus() {
		assertThatThrownBy(() -> jdbc.update("""
				insert into users (id, oidc_issuer, oidc_subject, email, display_name, status, created_at, updated_at)
				values (?, 'iss', 'sub', 'a@b.co', 'A', 'UNKNOWN', now(), now())
				""", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}

	private <T> T inTx(Supplier<T> action) {
		return tx.execute(status -> action.get());
	}

	private int countUsers() {
		return jdbc.queryForObject("select count(*) from users", Integer.class);
	}

}
