package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.forecast.application.exception.RecurringSeriesNotFoundException;
import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Ações do usuário em recorrências: confirmar, ignorar e editar valor e dia (ADR-0030 §2, ADR-0031 §5). Autoriza o
 * membro do Workspace antes de tudo; série inexistente ou de outro Workspace responde como inexistente.
 * <p>
 * Cada ação, na mesma transação, bloqueia o Workspace e o marca com {@code due_at = agora} (sem debounce), e só
 * depois grava a série: a ordem (Workspace, depois série) é a do recálculo e evita deadlock com o job. Não há evento de
 * integração nem recálculo na requisição; o snapshot fica pronto no próximo ciclo do job. Logs só com ids, ação e
 * status.
 */
@Service
public class RecurringSeriesActions {

    private static final Logger log = LoggerFactory.getLogger(RecurringSeriesActions.class);

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final RecurringSeriesRepository series;
    private final ForecastSnapshotStore store;
    private final Clock clock;
    private final MeterRegistry meters;

    public RecurringSeriesActions(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                                  RecurringSeriesRepository series, ForecastSnapshotStore store, Clock clock,
                                  MeterRegistry meters) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.series = series;
        this.store = store;
        this.clock = clock;
        this.meters = meters;
    }

    /** Valor e dia novos; nulo mantém o atual. Pelo menos um deve vir. */
    public record Edit(BigDecimal expectedAmount, Integer expectedDay) {}

    @Transactional
    public RecurringSeries confirm(UUID workspaceId, SeriesDirection direction, String key) {
        return apply(workspaceId, direction, key, "confirm", (current, workspace, now) -> current.confirm(now));
    }

    @Transactional
    public RecurringSeries ignore(UUID workspaceId, SeriesDirection direction, String key) {
        return apply(workspaceId, direction, key, "ignore", (current, workspace, now) -> current.ignore(now));
    }

    @Transactional
    public RecurringSeries edit(UUID workspaceId, SeriesDirection direction, String key, Edit edit) {
        return apply(workspaceId, direction, key, "edit", (current, workspace, now) -> {
            Money amount = edit.expectedAmount() == null ? null
                    : Money.of(edit.expectedAmount().toPlainString(), workspace.baseCurrency());
            return current.edit(amount, edit.expectedDay());
        });
    }

    private RecurringSeries apply(UUID workspaceId, SeriesDirection direction, String key, String action,
                                  Change change) {
        Workspace workspace = accessGuard.loadAsMember(currentUser.requireCurrentUserId(),
                new WorkspaceId(workspaceId));
        if (direction == null || key == null || key.isBlank()) {
            throw new InvalidValueException("key", "REQUIRED");
        }
        Instant now = clock.instant();
        // Primeiro o Workspace (mesma ordem do recálculo), depois a série.
        store.markDueNow(workspace.id(), now);
        RecurringSeries current = series.findForUpdate(workspace.id(), direction, key)
                .orElseThrow(RecurringSeriesNotFoundException::new);
        RecurringSeries updated = change.apply(current, workspace, now);
        if (!updated.equals(current)) {
            series.saveUserState(workspace.id(), updated, now);
        }
        meters.counter("shf.forecast.recurrence.action", "action", action).increment();
        log.info("Recurring series action workspaceId={} action={} direction={} status={}", workspace.id(), action,
                direction, updated.status());
        return updated;
    }

    @FunctionalInterface
    private interface Change {
        RecurringSeries apply(RecurringSeries current, Workspace workspace, Instant now);
    }
}
