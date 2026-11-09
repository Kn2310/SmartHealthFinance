package com.smarthealthfinance.consents.application.port;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

/**
 * A única parte do módulo de consentimentos que outro módulo pode usar (ADR-0038 §6). O chamador já autorizou o
 * Workspace. Sem cache: a revogação vale na próxima requisição.
 */
public interface ConsentReader {

    /** Verdadeiro só para um consentimento ativo na versão vigente do termo; pendente, expirado ou revogado = falso. */
    boolean isActive(WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose);
}
