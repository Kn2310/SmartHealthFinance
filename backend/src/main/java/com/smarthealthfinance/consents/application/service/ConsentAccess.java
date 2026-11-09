package com.smarthealthfinance.consents.application.service;

import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Autorização por membership (ADR-0003); a pessoa só vê e altera os próprios consentimentos (ADR-0038 §1). */
@Service
public class ConsentAccess {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;

    public ConsentAccess(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
    }

    public record Member(WorkspaceId workspaceId, UserId userId) {
    }

    /** @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership */
    public Member requireMember(UUID workspaceId) {
        WorkspaceId workspace = new WorkspaceId(workspaceId);
        UserId user = currentUser.requireCurrentUserId();
        accessGuard.requireMember(user, workspace);
        return new Member(workspace, user);
    }
}
