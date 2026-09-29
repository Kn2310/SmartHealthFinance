package com.smarthealthfinance.identity.application.service;

import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkspaceAccessGuardTest {

	private final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
	private final WorkspaceAccessGuard guard = new WorkspaceAccessGuard(workspaces);

	private final User ana = activeUser("sub-ana");
	private final User bob = activeUser("sub-bob");
	private final Workspace anasWorkspace = personalWorkspace(ana);

	@Test
	void memberIsAllowed() {
		workspaces.store(anasWorkspace);

		assertThatNoException().isThrownBy(() -> guard.requireMember(ana.id(), anasWorkspace.id()));
	}

	@Test
	void nonMemberGetsNotFound() {
		workspaces.store(anasWorkspace);

		assertThatThrownBy(() -> guard.requireMember(bob.id(), anasWorkspace.id()))
			.isInstanceOf(WorkspaceNotFoundException.class);
	}

	@Test
	void unknownWorkspaceGetsTheSameNotFound() {
		assertThatThrownBy(() -> guard.requireMember(ana.id(), WorkspaceId.generate(CREATED_AT)))
			.isInstanceOf(WorkspaceNotFoundException.class);
	}

}
