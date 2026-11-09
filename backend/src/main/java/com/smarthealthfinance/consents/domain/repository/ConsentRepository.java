package com.smarthealthfinance.consents.domain.repository;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.List;
import java.util.Optional;

/** Sempre pela pessoa dentro do Workspace: ninguém lê o consentimento de outra pessoa. */
public interface ConsentRepository {

    Optional<Consent> find(WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose);

    List<Consent> findAll(WorkspaceId workspaceId, UserId userId);

    /** @throws org.springframework.dao.OptimisticLockingFailureException se outra concessão chegou antes */
    void add(Consent consent);

    void save(Consent consent);
}
