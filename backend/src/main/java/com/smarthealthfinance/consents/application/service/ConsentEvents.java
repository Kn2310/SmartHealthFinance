package com.smarthealthfinance.consents.application.service;

import com.smarthealthfinance.consents.domain.enums.ConsentCause;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Eventos {@code consent.granted} e {@code consent.revoked} v1 (ADR-0038 §8), na outbox e na mesma transação, só
 * quando algo mudou. Payload só com ids e códigos. O consumidor de {@code consent.revoked} que apaga o histórico do
 * Copilot nasce na Sessão 8.2.
 */
@Service
public class ConsentEvents {

    public static final String GRANTED = "consent.granted";
    public static final String REVOKED = "consent.revoked";
    public static final int VERSION = 1;

    private final EventOutbox outbox;

    public ConsentEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void granted(Consent consent) {
        append(GRANTED, consent, ConsentCause.USER);
    }

    public void revoked(Consent consent, ConsentCause cause) {
        append(REVOKED, consent, cause);
    }

    private void append(String type, Consent consent, ConsentCause cause) {
        outbox.append(new IntegrationEvent(type, VERSION, consent.workspaceId().value(), "Consent",
                consent.id().value(), null, Map.of(
                        "consentId", consent.id().value().toString(),
                        "userId", consent.userId().value().toString(),
                        "purpose", consent.purpose().name(),
                        "termsVersion", consent.termsVersion(),
                        "cause", cause.name())));
    }
}
