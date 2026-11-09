package com.smarthealthfinance.consents.application.usecase;

import com.smarthealthfinance.consents.application.dto.ConsentView;
import com.smarthealthfinance.consents.application.exception.ConsentPurposeNotFoundException;
import com.smarthealthfinance.consents.application.service.ConsentAccess;
import com.smarthealthfinance.consents.application.service.ConsentEvents;
import com.smarthealthfinance.consents.domain.enums.ConsentAction;
import com.smarthealthfinance.consents.domain.enums.ConsentCause;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.exception.ConsentPrerequisiteMissingException;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.model.ConsentAuditEntry;
import com.smarthealthfinance.consents.domain.repository.ConsentAuditLog;
import com.smarthealthfinance.consents.domain.repository.ConsentRepository;
import com.smarthealthfinance.consents.domain.valueobject.ConsentId;
import com.smarthealthfinance.shared.domain.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Concede ou revoga um consentimento da própria pessoa (ADR-0038 §3). Idempotente: repetir o estado atual não grava,
 * não audita e não emite evento. Revogar uma finalidade revoga em cascata as que dependem dela. Devolve a lista
 * inteira, porque a cascata muda mais de um item.
 */
@Service
public class ChangeConsent {

    private static final Logger log = LoggerFactory.getLogger(ChangeConsent.class);

    private final ConsentAccess access;
    private final ConsentRepository consents;
    private final ConsentAuditLog audit;
    private final ConsentEvents events;
    private final Clock clock;
    private final MeterRegistry meters;

    public ChangeConsent(ConsentAccess access, ConsentRepository consents, ConsentAuditLog audit,
                         ConsentEvents events, Clock clock, MeterRegistry meters) {
        this.access = access;
        this.consents = consents;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
    }

    /** @param termsVersion obrigatória e vigente para conceder; ignorada para revogar */
    public record Command(boolean granted, String termsVersion) {
    }

    @Transactional
    public List<ConsentView> execute(UUID workspaceId, String purposeName, Command command) {
        ConsentAccess.Member member = access.requireMember(workspaceId);
        ConsentPurpose purpose = ConsentPurpose.parse(purposeName).orElseThrow(ConsentPurposeNotFoundException::new);
        Instant now = clock.instant();

        if (command.granted()) {
            grant(member, purpose, command.termsVersion(), now);
        } else {
            revoke(member, purpose, ConsentCause.USER, now);
        }
        return ListConsents.viewsOf(consents, member.workspaceId(), member.userId());
    }

    private void grant(ConsentAccess.Member member, ConsentPurpose purpose, String termsVersion, Instant now) {
        purpose.requireCurrentTerms(termsVersion);
        Optional<ConsentPurpose> prerequisite = purpose.prerequisite();
        if (prerequisite.isPresent() && !isActive(member, prerequisite.get())) {
            throw new ConsentPrerequisiteMissingException(purpose);
        }

        Optional<Consent> existing = consents.find(member.workspaceId(), member.userId(), purpose);
        Consent consent;
        if (existing.isEmpty()) {
            consent = Consent.grant(ConsentId.generate(now), member.workspaceId(), member.userId(), purpose,
                    termsVersion, now);
            consents.add(consent);
        } else {
            consent = existing.get();
            if (!consent.regrant(termsVersion, now)) {
                return;
            }
            consents.save(consent);
        }
        record(consent, ConsentAction.GRANTED, ConsentCause.USER, now);
        events.granted(consent);
    }

    private void revoke(ConsentAccess.Member member, ConsentPurpose purpose, ConsentCause cause, Instant now) {
        Optional<Consent> existing = consents.find(member.workspaceId(), member.userId(), purpose);
        if (existing.isPresent() && existing.get().revoke(now)) {
            Consent consent = existing.get();
            consents.save(consent);
            record(consent, ConsentAction.REVOKED, cause, now);
            events.revoked(consent, cause);
        }
        for (ConsentPurpose dependent : purpose.dependents()) {
            revoke(member, dependent, ConsentCause.CASCADE, now);
        }
    }

    private boolean isActive(ConsentAccess.Member member, ConsentPurpose purpose) {
        return consents.find(member.workspaceId(), member.userId(), purpose).map(Consent::isActive).orElse(false);
    }

    private void record(Consent consent, ConsentAction action, ConsentCause cause, Instant now) {
        audit.append(new ConsentAuditEntry(UuidV7.generate(now), consent, action, cause, now));
        meters.counter("shf.consents.changes", "purpose", consent.purpose().name(), "action", action.name(),
                "cause", cause.name()).increment();
        log.info("Consent changed consentId={} workspaceId={} purpose={} action={} cause={}", consent.id(),
                consent.workspaceId(), consent.purpose(), action, cause);
    }
}
