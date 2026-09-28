package com.smarthealthfinance.identity.application;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.disabledUser;
import static com.smarthealthfinance.identity.IdentityFixtures.externalIdentity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.smarthealthfinance.identity.domain.Email;
import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserDisabledException;
import com.smarthealthfinance.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProvisionCurrentUserTest {

	private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

	private final InMemoryUserRepository users = new InMemoryUserRepository();
	private AuthenticatedIdentity identity = identity("Ana.Silva@Example.com", "Ana Silva", "ana");
	private final ProvisionCurrentUser provision = new ProvisionCurrentUser(() -> identity, users,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void createsActiveUserFromClaims() {
		ProvisionCurrentUser.Result result = provision.execute();

		assertThat(result.created()).isTrue();
		assertThat(result.user().email()).isEqualTo("ana.silva@example.com");
		assertThat(result.user().displayName()).isEqualTo("Ana Silva");
		assertThat(result.user().status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(result.user().createdAt()).isEqualTo(NOW);
		assertThat(result.user().id().version()).isEqualTo(7);
		assertThat(users.size()).isEqualTo(1);
	}

	@Test
	void isIdempotent() {
		ProvisionCurrentUser.Result first = provision.execute();
		ProvisionCurrentUser.Result second = provision.execute();

		assertThat(second.created()).isFalse();
		assertThat(second.user().id()).isEqualTo(first.user().id());
		assertThat(users.size()).isEqualTo(1);
		assertThat(users.saveCount()).isZero();
	}

	@Test
	void syncsEmailWhenIdpChangesIt() {
		User existing = activeUser("sub-ana", "ana.antiga@example.com", "Ana Silva");
		users.store(existing);

		ProvisionCurrentUser.Result result = provision.execute();

		assertThat(result.created()).isFalse();
		assertThat(result.user().email()).isEqualTo("ana.silva@example.com");
		assertThat(users.saveCount()).isEqualTo(1);
		User stored = users.findById(existing.id()).orElseThrow();
		assertThat(stored.email()).isEqualTo(new Email("ana.silva@example.com"));
		assertThat(stored.updatedAt()).isEqualTo(NOW);
	}

	@Test
	void keepsLocallyEditedDisplayName() {
		users.store(activeUser("sub-ana", "ana.silva@example.com", "Ana Souza"));

		ProvisionCurrentUser.Result result = provision.execute();

		assertThat(result.user().displayName()).isEqualTo("Ana Souza");
		assertThat(users.saveCount()).isZero();
	}

	@Test
	void usesPreferredUsernameWhenNameIsBlank() {
		identity = identity("ana@example.com", "  ", "ana.s");

		assertThat(provision.execute().user().displayName()).isEqualTo("ana.s");
	}

	@Test
	void usesEmailLocalPartWhenNoNameClaims() {
		identity = identity("ana.silva@example.com", null, null);

		assertThat(provision.execute().user().displayName()).isEqualTo("ana.silva");
	}

	@Test
	void stripsAndTruncatesLongNames() {
		identity = identity("ana@example.com", "  " + "A".repeat(150) + "  ", null);

		assertThat(provision.execute().user().displayName()).isEqualTo("A".repeat(100));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "not-an-email", "ana@" })
	void rejectsMissingOrInvalidEmailClaim(String email) {
		identity = identity(email, "Ana", null);

		assertThatThrownBy(provision::execute).isInstanceOf(IncompleteIdentityClaimsException.class);
		assertThat(users.size()).isZero();
	}

	@Test
	void rejectsDisabledUser() {
		users.store(disabledUser("sub-ana"));

		assertThatThrownBy(provision::execute).isInstanceOf(UserDisabledException.class);
		assertThat(users.saveCount()).isZero();
	}

	@Test
	void concurrentProvisioningReturnsTheWinner() {
		User winner = activeUser("sub-ana", "ana.silva@example.com", "Ana Silva");
		users.simulateConcurrentProvisioning(winner);

		ProvisionCurrentUser.Result result = provision.execute();

		assertThat(result.created()).isFalse();
		assertThat(result.user().id()).isEqualTo(winner.id().value());
		assertThat(users.size()).isEqualTo(1);
	}

	private static AuthenticatedIdentity identity(String email, String name, String preferredUsername) {
		return new AuthenticatedIdentity(externalIdentity("sub-ana"), email, name, preferredUsername);
	}

}
