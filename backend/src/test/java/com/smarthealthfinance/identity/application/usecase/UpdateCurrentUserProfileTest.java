package com.smarthealthfinance.identity.application.usecase;

import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.dto.UserView;
import com.smarthealthfinance.identity.application.exception.UserNotProvisionedException;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.domain.exception.UserDisabledException;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.valueobject.DisplayName;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.disabledUser;
import static com.smarthealthfinance.identity.IdentityFixtures.externalIdentity;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class UpdateCurrentUserProfileTest {

	private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

	private final InMemoryUserRepository users = new InMemoryUserRepository();
	private final AuthenticatedIdentity identity = new AuthenticatedIdentity(externalIdentity("sub-ana"),
			"ana@example.com", null, null);
	private final UpdateCurrentUserProfile update = new UpdateCurrentUserProfile(
			new CurrentUserService(() -> identity, users), users, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void renamesAndPersists() {
		User user = activeUser("sub-ana");
		users.store(user);

		UserView view = update.execute(new UpdateCurrentUserProfile.Command("  Ana Souza "));

		assertThat(view.displayName()).isEqualTo("Ana Souza");
		assertThat(users.saveCount()).isEqualTo(1);
		User stored = users.findById(user.id()).orElseThrow();
		assertThat(stored.displayName()).isEqualTo(new DisplayName("Ana Souza"));
		assertThat(stored.updatedAt()).isEqualTo(NOW);
	}

	@Test
	void rejectsInvalidNameWithoutSaving() {
		users.store(activeUser("sub-ana"));

		assertInvalidValue(() -> update.execute(new UpdateCurrentUserProfile.Command("")), "displayName", "REQUIRED");
		assertThat(users.saveCount()).isZero();
	}

	@Test
	void failsWhenNotProvisioned() {
		assertThatThrownBy(() -> update.execute(new UpdateCurrentUserProfile.Command("Ana")))
			.isInstanceOf(UserNotProvisionedException.class);
	}

	@Test
	void failsWhenDisabled() {
		users.store(disabledUser("sub-ana"));

		assertThatThrownBy(() -> update.execute(new UpdateCurrentUserProfile.Command("Ana")))
			.isInstanceOf(UserDisabledException.class);
		assertThat(users.saveCount()).isZero();
	}

}
