package com.smarthealthfinance.identity.domain;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.disabledUser;
import static com.smarthealthfinance.identity.IdentityFixtures.externalIdentity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserTest {

	private static final Instant LATER = CREATED_AT.plus(Duration.ofDays(1));

	@Test
	void provisionCreatesActiveUser() {
		User user = User.provision(UserId.generate(CREATED_AT), externalIdentity("sub-ana"),
				new Email("ana@example.com"), new DisplayName("Ana Silva"), CREATED_AT);

		assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(user.createdAt()).isEqualTo(CREATED_AT);
		assertThat(user.updatedAt()).isEqualTo(CREATED_AT);
		assertThat(user.version()).isZero();
		assertThat(user.externalIdentity()).isEqualTo(externalIdentity("sub-ana"));
	}

	@Test
	void provisionRequiresAllFields() {
		assertThatNullPointerException()
			.isThrownBy(() -> User.provision(UserId.generate(CREATED_AT), externalIdentity("sub-ana"), null,
					new DisplayName("Ana"), CREATED_AT))
			.withMessage("email");
	}

	@Test
	void restorePreservesPersistedState() {
		UserId id = UserId.generate(CREATED_AT);

		User user = User.restore(id, externalIdentity("sub-ana"), new Email("ana@example.com"),
				new DisplayName("Ana"), UserStatus.DISABLED, CREATED_AT, LATER, 7);

		assertThat(user.id()).isEqualTo(id);
		assertThat(user.status()).isEqualTo(UserStatus.DISABLED);
		assertThat(user.updatedAt()).isEqualTo(LATER);
		assertThat(user.version()).isEqualTo(7);
	}

	@Test
	void renameChangesNameAndTouchesUpdatedAt() {
		User user = activeUser("sub-ana");

		user.rename(new DisplayName("Ana Souza"), LATER);

		assertThat(user.displayName()).isEqualTo(new DisplayName("Ana Souza"));
		assertThat(user.updatedAt()).isEqualTo(LATER);
	}

	@Test
	void renameToSameNameIsNoOp() {
		User user = activeUser("sub-ana", "ana@example.com", "Ana Silva");

		user.rename(new DisplayName("Ana Silva"), LATER);

		assertThat(user.updatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void syncEmailReportsChange() {
		User user = activeUser("sub-ana", "ana@example.com", "Ana");

		assertThat(user.syncEmail(new Email("ana.nova@example.com"), LATER)).isTrue();
		assertThat(user.email()).isEqualTo(new Email("ana.nova@example.com"));
		assertThat(user.updatedAt()).isEqualTo(LATER);
	}

	@Test
	void syncEmailWithSameEmailIsNoOp() {
		User user = activeUser("sub-ana", "ana@example.com", "Ana");

		assertThat(user.syncEmail(new Email("ANA@example.com"), LATER)).isFalse();
		assertThat(user.updatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void disableIsIdempotent() {
		User user = activeUser("sub-ana");

		user.disable(LATER);
		user.disable(LATER.plusSeconds(60));

		assertThat(user.status()).isEqualTo(UserStatus.DISABLED);
		assertThat(user.updatedAt()).isEqualTo(LATER);
	}

	@Test
	void disabledUserCannotChange() {
		User user = disabledUser("sub-ana");

		assertThatThrownBy(user::ensureActive).isInstanceOf(UserDisabledException.class);
		assertThatThrownBy(() -> user.rename(new DisplayName("Outro"), LATER))
			.isInstanceOf(UserDisabledException.class);
		assertThatThrownBy(() -> user.syncEmail(new Email("outro@example.com"), LATER))
			.isInstanceOf(UserDisabledException.class);
		assertThat(user.displayName()).isEqualTo(new DisplayName("Ana Silva"));
	}

	@Test
	void activeUserPassesEnsureActive() {
		assertThatNoException().isThrownBy(activeUser("sub-ana")::ensureActive);
	}

}
