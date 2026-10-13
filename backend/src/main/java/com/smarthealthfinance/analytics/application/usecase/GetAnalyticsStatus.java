package com.smarthealthfinance.analytics.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Freshness da projeção de Analytics do Workspace (ADR-0014 §6). Autoriza o membro antes de ler. */
@Service
public class GetAnalyticsStatus {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final AnalyticsReadModel readModel;

    public GetAnalyticsStatus(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                              AnalyticsReadModel readModel) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.readModel = readModel;
    }

    @Transactional(readOnly = true)
    public ProjectionFreshness execute(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return readModel.freshness(id);
    }
}
