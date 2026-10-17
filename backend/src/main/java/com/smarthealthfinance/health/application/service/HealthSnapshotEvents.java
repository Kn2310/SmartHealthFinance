package com.smarthealthfinance.health.application.service;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Evento {@code health.snapshot-updated} v1 (adendo de 2026-10-16 ao ADR-0018 §2; ADR-0022 §2), gravado na outbox na
 * mesma transação de cada recálculo do snapshot (job e rebuild): quem lê o snapshot (os Insights) só reage depois que
 * ele já foi gravado. Payload só com a versão do algoritmo.
 */
@Service
public class HealthSnapshotEvents {

    public static final String SNAPSHOT_UPDATED = "health.snapshot-updated";
    public static final int VERSION = 1;

    private final EventOutbox outbox;

    public HealthSnapshotEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void updated(WorkspaceId workspaceId, String algorithmVersion) {
        outbox.append(new IntegrationEvent(SNAPSHOT_UPDATED, VERSION, workspaceId.value(), "HealthSnapshot",
                workspaceId.value(), null, Map.of("algorithmVersion", algorithmVersion)));
    }
}
