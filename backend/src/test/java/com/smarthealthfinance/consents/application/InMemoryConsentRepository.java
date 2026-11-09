package com.smarthealthfinance.consents.application;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.model.ConsentAuditEntry;
import com.smarthealthfinance.consents.domain.repository.ConsentAuditLog;
import com.smarthealthfinance.consents.domain.repository.ConsentRepository;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fake próximo do adapter: chave (Workspace, pessoa, finalidade), cópias sem aliasing, version no save. */
public final class InMemoryConsentRepository implements ConsentRepository, ConsentAuditLog {

    private record Key(WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose) {
    }

    private final Map<Key, Consent> consents = new LinkedHashMap<>();
    public final List<ConsentAuditEntry> audit = new ArrayList<>();

    @Override
    public Optional<Consent> find(WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose) {
        return Optional.ofNullable(consents.get(new Key(workspaceId, userId, purpose))).map(c -> copy(c, c.version()));
    }

    @Override
    public List<Consent> findAll(WorkspaceId workspaceId, UserId userId) {
        return consents.values().stream()
                .filter(c -> c.workspaceId().equals(workspaceId) && c.userId().equals(userId))
                .map(c -> copy(c, c.version()))
                .toList();
    }

    @Override
    public void add(Consent consent) {
        Key key = keyOf(consent);
        if (consents.containsKey(key)) {
            throw new OptimisticLockingFailureException("duplicate");
        }
        consents.put(key, copy(consent, consent.version()));
    }

    @Override
    public void save(Consent consent) {
        Key key = keyOf(consent);
        Consent stored = consents.get(key);
        if (stored == null || stored.version() != consent.version()) {
            throw new OptimisticLockingFailureException("stale");
        }
        consents.put(key, copy(consent, consent.version() + 1));
    }

    @Override
    public void append(ConsentAuditEntry entry) {
        audit.add(entry);
    }

    public int size() {
        return consents.size();
    }

    private static Key keyOf(Consent consent) {
        return new Key(consent.workspaceId(), consent.userId(), consent.purpose());
    }

    private static Consent copy(Consent c, long version) {
        return Consent.restore(c.id(), c.workspaceId(), c.userId(), c.purpose(), c.status(), c.termsVersion(),
                c.grantedAt(), c.revokedAt().orElse(null), c.createdAt(), c.updatedAt(), version);
    }
}
