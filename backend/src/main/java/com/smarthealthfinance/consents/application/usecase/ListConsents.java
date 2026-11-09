package com.smarthealthfinance.consents.application.usecase;

import com.smarthealthfinance.consents.application.dto.ConsentView;
import com.smarthealthfinance.consents.application.service.ConsentAccess;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.repository.ConsentRepository;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Todas as finalidades da pessoa no Workspace, na ordem do enum; as nunca respondidas como PENDING (ADR-0038 §7). */
@Service
public class ListConsents {

    private final ConsentAccess access;
    private final ConsentRepository consents;

    public ListConsents(ConsentAccess access, ConsentRepository consents) {
        this.access = access;
        this.consents = consents;
    }

    @Transactional(readOnly = true)
    public List<ConsentView> execute(UUID workspaceId) {
        ConsentAccess.Member member = access.requireMember(workspaceId);
        return viewsOf(consents, member.workspaceId(), member.userId());
    }

    static List<ConsentView> viewsOf(ConsentRepository consents, WorkspaceId workspaceId, UserId userId) {
        Map<ConsentPurpose, Consent> existing = consents.findAll(workspaceId, userId).stream()
                .collect(Collectors.toMap(Consent::purpose, Function.identity()));
        return Arrays.stream(ConsentPurpose.values())
                .map(purpose -> existing.containsKey(purpose)
                        ? ConsentView.from(existing.get(purpose))
                        : ConsentView.pending(purpose))
                .toList();
    }
}
