package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.WorkspaceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GetWorkspace {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;

    public GetWorkspace(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public WorkspaceView execute(UUID workspaceId) {
        UserId userId = currentUser.requireActiveUser().id();

        return WorkspaceView.from(accessGuard.loadAsMember(userId, new WorkspaceId(workspaceId)), userId);
    }
}
