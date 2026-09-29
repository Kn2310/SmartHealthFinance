package com.smarthealthfinance.identity.application.service;

import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.exception.UserNotProvisionedException;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.domain.exception.UserDisabledException;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.valueobject.DisplayName;
import com.smarthealthfinance.identity.domain.valueobject.Email;
import com.smarthealthfinance.identity.domain.valueobject.ExternalIdentity;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.disabledUser;
import static com.smarthealthfinance.identity.IdentityFixtures.externalIdentity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CurrentUserServiceTest {

	private final InMemoryUserRepository users = new InMemoryUserRepository();
	private final AuthenticatedIdentity identity = new AuthenticatedIdentity(externalIdentity("sub-ana"),
			"ana@example.com", null, null);
	private final CurrentUserService service = new CurrentUserService(() -> identity, users);

	@Test
	void returnsIdOfProvisionedUser() {
		User user = activeUser("sub-ana");
		users.store(user);

		assertThat(service.requireCurrentUserId()).isEqualTo(user.id());
	}

	@Test
	void failsWhenUserIsNotProvisioned() {
		assertThatThrownBy(service::requireCurrentUserId).isInstanceOf(UserNotProvisionedException.class);
	}

	@Test
	void failsWhenUserIsDisabled() {
		users.store(disabledUser("sub-ana"));

		assertThatThrownBy(service::requireCurrentUserId).isInstanceOf(UserDisabledException.class);
	}

	@Test
	void doesNotResolveSameSubjectFromAnotherIssuer() {
		users.store(User.provision(UserId.generate(CREATED_AT), new ExternalIdentity("https://other.idp", "sub-ana"),
				new Email("ana@example.com"), new DisplayName("Ana"), CREATED_AT));

		assertThatThrownBy(service::requireCurrentUserId).isInstanceOf(UserNotProvisionedException.class);
	}

}
