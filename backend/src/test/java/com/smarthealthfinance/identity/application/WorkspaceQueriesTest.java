package com.smarthealthfinance.identity.application;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.disabledUser;
import static com.smarthealthfinance.identity.IdentityFixtures.externalIdentity;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserDisabledException;
import com.smarthealthfinance.identity.domain.Workspace;
import com.smarthealthfinance.identity.domain.WorkspaceKind;
import com.smarthealthfinance.identity.domain.WorkspaceRole;
import org.junit.jupiter.api.Test;

/** ListCurrentUserWorkspaces + GetWorkspace: ambos resolvem o usuário corrente e autorizam via membership. */
class WorkspaceQueriesTest {

	private final InMemoryUserRepository users = new InMemoryUserRepository();
	private final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
	private final AuthenticatedIdentity anaIdentity = new AuthenticatedIdentity(externalIdentity("sub-ana"),
			"ana@example.com", null, null);
	private final CurrentUserService currentUser = new CurrentUserService(() -> anaIdentity, users);
	private final ListCurrentUserWorkspaces list = new ListCurrentUserWorkspaces(currentUser, workspaces);
	private final GetWorkspace get = new GetWorkspace(currentUser, new WorkspaceAccessGuard(workspaces));

	private final User ana = activeUser("sub-ana");
	private final User bob = activeUser("sub-bob");
	private final Workspace anasWorkspace = personalWorkspace(ana);
	private final Workspace bobsWorkspace = personalWorkspace(bob);

	@Test
	void listsOnlyWorkspacesWhereCurrentUserIsMember() {
		users.store(ana);
		workspaces.store(anasWorkspace);
		workspaces.store(bobsWorkspace);

		assertThat(list.execute()).singleElement().satisfies(view -> {
			assertThat(view.id()).isEqualTo(anasWorkspace.id().value());
			assertThat(view.name()).isEqualTo("Pessoal");
			assertThat(view.kind()).isEqualTo(WorkspaceKind.PERSONAL);
			assertThat(view.baseCurrency()).isEqualTo("BRL");
			assertThat(view.role()).isEqualTo(WorkspaceRole.OWNER);
		});
	}

	@Test
	void listIsEmptyWhenUserHasNoWorkspaceYet() {
		users.store(ana);

		assertThat(list.execute()).isEmpty();
	}

	@Test
	void getsOwnWorkspace() {
		users.store(ana);
		workspaces.store(anasWorkspace);

		assertThat(get.execute(anasWorkspace.id().value()).id()).isEqualTo(anasWorkspace.id().value());
	}

	@Test
	void cannotGetAnotherUsersWorkspace() {
		users.store(ana);
		workspaces.store(bobsWorkspace);

		assertThatThrownBy(() -> get.execute(bobsWorkspace.id().value()))
			.isInstanceOf(WorkspaceNotFoundException.class);
	}

	@Test
	void requiresProvisionedUser() {
		assertThatThrownBy(list::execute).isInstanceOf(UserNotProvisionedException.class);
		assertThatThrownBy(() -> get.execute(anasWorkspace.id().value()))
			.isInstanceOf(UserNotProvisionedException.class);
	}

	@Test
	void disabledUserCannotQueryWorkspaces() {
		User disabled = disabledUser("sub-ana");
		users.store(disabled);
		Workspace workspace = personalWorkspace(disabled);
		workspaces.store(workspace);

		assertThatThrownBy(list::execute).isInstanceOf(UserDisabledException.class);
		assertThatThrownBy(() -> get.execute(workspace.id().value())).isInstanceOf(UserDisabledException.class);
	}

}
