package com.smarthealthfinance.identity.application.usecase;

import com.smarthealthfinance.identity.application.dto.WorkspaceView;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
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
