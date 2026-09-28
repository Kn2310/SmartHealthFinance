package com.smarthealthfinance.identity.application;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.externalIdentity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;

class GetCurrentUserTest {

	private final InMemoryUserRepository users = new InMemoryUserRepository();
	private final AuthenticatedIdentity identity = new AuthenticatedIdentity(externalIdentity("sub-ana"),
			"ana@example.com", null, null);
	private final GetCurrentUser getCurrentUser = new GetCurrentUser(new CurrentUserService(() -> identity, users));

	@Test
	void returnsViewOfCurrentUser() {
		User user = activeUser("sub-ana", "ana@example.com", "Ana Silva");
		users.store(user);

		UserView view = getCurrentUser.execute();

		assertThat(view).isEqualTo(new UserView(user.id().value(), "ana@example.com", "Ana Silva",
				UserStatus.ACTIVE, user.createdAt()));
	}

	@Test
	void failsWhenNotProvisioned() {
		assertThatThrownBy(getCurrentUser::execute).isInstanceOf(UserNotProvisionedException.class);
	}

}
