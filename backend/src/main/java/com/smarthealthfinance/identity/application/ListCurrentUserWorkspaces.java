package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ListCurrentUserWorkspaces {

    private final CurrentUserService currentUser;
    private final WorkspaceRepository workspaces;

    public ListCurrentUserWorkspaces(CurrentUserService currentUser, WorkspaceRepository workspaces) {
        this.currentUser = currentUser;
        this.workspaces = workspaces;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceView> execute() {
        UserId userId = currentUser.requireActiveUser().id();

        return workspaces.findAllByMember(userId).stream()
                .map(workspace -> WorkspaceView.from(workspace, userId))
                .toList();
    }
}
