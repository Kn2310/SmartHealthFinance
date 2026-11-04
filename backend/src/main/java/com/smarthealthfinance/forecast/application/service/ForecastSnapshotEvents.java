package com.smarthealthfinance.forecast.application.service;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Evento {@code forecast.snapshot-updated} v1 (ADR-0031 §2), gravado na outbox na mesma transação de cada
 * recálculo do snapshot (job e rebuild): quem lê o snapshot (os Insights) só reage depois que ele já foi gravado.
 * Payload só com a versão do algoritmo: nenhum valor, nome ou texto.
 */
@Service
public class ForecastSnapshotEvents {

    public static final String SNAPSHOT_UPDATED = "forecast.snapshot-updated";
    public static final int VERSION = 1;

    private final EventOutbox outbox;

    public ForecastSnapshotEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void updated(WorkspaceId workspaceId, String algorithmVersion) {
        outbox.append(new IntegrationEvent(SNAPSHOT_UPDATED, VERSION, workspaceId.value(), "ForecastSnapshot",
                workspaceId.value(), null, Map.of("algorithmVersion", algorithmVersion)));
    }
}
