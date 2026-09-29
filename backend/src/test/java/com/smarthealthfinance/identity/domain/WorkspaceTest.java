package com.smarthealthfinance.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static com.smarthealthfinance.identity.IdentityFixtures.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceTest {

    private static final Instant LATER = CREATED_AT.plus(Duration.ofDays(1));

    private final UserId ana = activeUser("sub-ana").id();
    private final UserId bob = activeUser("sub-bob").id();

    @Test
    void createPersonalAppliesMvpDefaults() {
        WorkspaceId id = WorkspaceId.generate(CREATED_AT);

        Workspace workspace = Workspace.createPersonal(id, ana, CREATED_AT);

        assertThat(workspace.id()).isEqualTo(id);
        assertThat(workspace.kind()).isEqualTo(WorkspaceKind.PERSONAL);
        assertThat(workspace.ownerId()).isEqualTo(ana);
        assertThat(workspace.name()).isEqualTo(new WorkspaceName("Pessoal"));
        assertThat(workspace.baseCurrency()).isEqualTo(Currency.getInstance("BRL"));
        assertThat(workspace.createdAt()).isEqualTo(CREATED_AT);
        assertThat(workspace.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(workspace.version()).isZero();
    }

    @Test
    void createPersonalMakesOwnerTheOnlyMember() {
        Workspace workspace = Workspace.createPersonal(WorkspaceId.generate(CREATED_AT), ana, CREATED_AT);

        assertThat(workspace.memberships())
                .containsExactly(new WorkspaceMembership(ana, WorkspaceRole.OWNER, CREATED_AT));
    }

    @Test
    void createPersonalRequiresOwner() {
        assertThatNullPointerException()
                .isThrownBy(() -> Workspace.createPersonal(WorkspaceId.generate(CREATED_AT), null, CREATED_AT));
    }

    @Test
    void memberHasOwnerRole() {
        Workspace workspace = Workspace.createPersonal(WorkspaceId.generate(CREATED_AT), ana, CREATED_AT);

        assertThat(workspace.isMember(ana)).isTrue();
        assertThat(workspace.roleOf(ana)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void nonMemberHasNoAccess() {
        Workspace workspace = Workspace.createPersonal(WorkspaceId.generate(CREATED_AT), ana, CREATED_AT);

        assertThat(workspace.isMember(bob)).isFalse();
        assertThat(workspace.roleOf(bob)).isEmpty();
    }

    @Test
    void restorePreservesPersistedState() {
        WorkspaceId id = WorkspaceId.generate(CREATED_AT);
        List<WorkspaceMembership> memberships = List.of(new WorkspaceMembership(ana, WorkspaceRole.OWNER, CREATED_AT));

        Workspace workspace = Workspace.restore(id, WorkspaceKind.PERSONAL, ana, new WorkspaceName("Casa"),
                Currency.getInstance("BRL"), memberships, CREATED_AT, LATER, 3);

        assertThat(workspace.name()).isEqualTo(new WorkspaceName("Casa"));
        assertThat(workspace.updatedAt()).isEqualTo(LATER);
        assertThat(workspace.version()).isEqualTo(3);
        assertThat(workspace.memberships()).isEqualTo(memberships);
    }

    @Test
    void restoreRejectsOwnerWithoutOwnerMembership() {
        assertThatIllegalArgumentException().isThrownBy(() -> restoreWith(ana, List.of()));
    }

    @Test
    void restoreRejectsDuplicateMembership() {
        List<WorkspaceMembership> duplicated = List.of(new WorkspaceMembership(ana, WorkspaceRole.OWNER, CREATED_AT),
                new WorkspaceMembership(ana, WorkspaceRole.OWNER, LATER));

        assertThatIllegalArgumentException().isThrownBy(() -> restoreWith(ana, duplicated));
    }

    @Test
    void membershipsCannotBeChangedFromOutside() {
        List<WorkspaceMembership> source = new ArrayList<>(
                List.of(new WorkspaceMembership(ana, WorkspaceRole.OWNER, CREATED_AT)));
        Workspace workspace = restoreWith(ana, source);

        source.add(new WorkspaceMembership(bob, WorkspaceRole.OWNER, LATER));

        assertThat(workspace.isMember(bob)).isFalse();
        assertThatThrownBy(() -> workspace.memberships().add(new WorkspaceMembership(bob, WorkspaceRole.OWNER, LATER)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fixtureBuildsPersonalWorkspaceForUser() {
        User owner = activeUser("sub-ana");

        assertThat(personalWorkspace(owner).isMember(owner.id())).isTrue();
    }

    private static Workspace restoreWith(UserId owner, List<WorkspaceMembership> memberships) {
        return Workspace.restore(WorkspaceId.generate(CREATED_AT), WorkspaceKind.PERSONAL, owner,
                WorkspaceName.PERSONAL_DEFAULT, Currency.getInstance("BRL"), memberships, CREATED_AT, CREATED_AT, 0);
    }

}
