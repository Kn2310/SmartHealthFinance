package com.smarthealthfinance.identity;

import java.time.Instant;

import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.DisplayName;
import com.smarthealthfinance.identity.domain.valueobject.Email;
import com.smarthealthfinance.identity.domain.valueobject.ExternalIdentity;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

public final class IdentityFixtures {

	public static final String ISSUER = "http://issuer.test/realms/smart-health-finance";

	/** Sem frações abaixo de microssegundo: sobrevive ao round-trip no timestamptz. */
	public static final Instant CREATED_AT = Instant.parse("2026-01-10T09:00:00Z");

	private IdentityFixtures() {
	}

	public static ExternalIdentity externalIdentity(String subject) {
		return new ExternalIdentity(ISSUER, subject);
	}

	public static User activeUser(String subject) {
		return activeUser(subject, subject + "@example.com", "Ana Silva");
	}

	public static User activeUser(String subject, String email, String displayName) {
		return User.provision(UserId.generate(CREATED_AT), externalIdentity(subject), new Email(email),
				new DisplayName(displayName), CREATED_AT);
	}

	public static User disabledUser(String subject) {
		User user = activeUser(subject);
		user.disable(CREATED_AT);
		return user;
	}

	public static Workspace personalWorkspace(User owner) {
		return Workspace.createPersonal(WorkspaceId.generate(CREATED_AT), owner.id(), CREATED_AT);
	}
}
