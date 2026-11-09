package com.smarthealthfinance.consents.application.service;

import com.smarthealthfinance.consents.application.port.ConsentReader;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.repository.ConsentRepository;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementação da {@link ConsentReader}: uma leitura por chave a cada chamada (ADR-0038 §4, §6). */
@Service
public class ActiveConsents implements ConsentReader {

    private final ConsentRepository consents;

    public ActiveConsents(ConsentRepository consents) {
        this.consents = consents;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose) {
        return consents.find(workspaceId, userId, purpose).map(Consent::isActive).orElse(false);
    }
}
