package com.smarthealthfinance.analytics.application.service;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Evento {@code analytics.projection-updated} v1 (adendo de 2026-10-15 ao ADR-0014; ADR-0018 §2), gravado na outbox
 * na mesma transação do recálculo ou do rebuild: quem lê a projeção (o Health) só reage depois que ela já reflete o
 * fato. Payload só com os meses ({@code YYYY-MM}); vazio = rebuild (todos os meses).
 */
@Service
public class AnalyticsProjectionEvents {

    public static final String PROJECTION_UPDATED = "analytics.projection-updated";
    public static final int VERSION = 1;

    private final EventOutbox outbox;

    public AnalyticsProjectionEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void monthsUpdated(WorkspaceId workspaceId, Collection<YearMonth> months) {
        append(workspaceId, new TreeSet<>(months).stream().map(YearMonth::toString).toList());
    }

    public void rebuilt(WorkspaceId workspaceId) {
        append(workspaceId, List.of());
    }

    private void append(WorkspaceId workspaceId, List<String> months) {
        outbox.append(new IntegrationEvent(PROJECTION_UPDATED, VERSION, workspaceId.value(), "AnalyticsProjection",
                workspaceId.value(), null, Map.of("months", months)));
    }
}
