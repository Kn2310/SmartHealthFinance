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

    /** Valor e dia novos; nulo mantém o atual. Pelo menos um deve vir. {@code currency} nula = a do Workspace. */
    public record Edit(BigDecimal expectedAmount, Integer expectedDay, String currency) {

        public Edit(BigDecimal expectedAmount, Integer expectedDay) {
            this(expectedAmount, expectedDay, null);
        }
    }

    @Transactional
    public RecurringSeries confirm(UUID workspaceId, SeriesDirection direction, String key) {
        return apply(workspaceId, direction, key, null, "confirm", (current, workspace, now) -> current.confirm(now));
    }

    @Transactional
    public RecurringSeries ignore(UUID workspaceId, SeriesDirection direction, String key) {
        return apply(workspaceId, direction, key, null, "ignore", (current, workspace, now) -> current.ignore(now));
    }

    @Transactional
    public RecurringSeries edit(UUID workspaceId, SeriesDirection direction, String key, Edit edit) {
        return apply(workspaceId, direction, key, null, "edit", editing(edit));
    }

    // Pelo id opaco da API (ADR-0032 §2): série inexistente, de outro Workspace ou da outra direção = não encontrada.

    @Transactional
    public RecurringSeries confirm(UUID workspaceId, SeriesDirection direction, UUID seriesId) {
        return apply(workspaceId, direction, null, seriesId, "confirm", (current, workspace, now) -> current.confirm(now));
    }

    @Transactional
    public RecurringSeries ignore(UUID workspaceId, SeriesDirection direction, UUID seriesId) {
        return apply(workspaceId, direction, null, seriesId, "ignore", (current, workspace, now) -> current.ignore(now));
    }

    @Transactional
    public RecurringSeries edit(UUID workspaceId, SeriesDirection direction, UUID seriesId, Edit edit) {
        return apply(workspaceId, direction, null, seriesId, "edit", editing(edit));
    }

    /** Remove o ajuste do usuário: voltam os valores detectados (ADR-0032 §2). Idempotente. */
    @Transactional
    public RecurringSeries clearAdjustment(UUID workspaceId, SeriesDirection direction, UUID seriesId) {
        return apply(workspaceId, direction, null, seriesId, "clear_adjustment",
                (current, workspace, now) -> current.clearAdjustment());
    }

    /** Valida o corpo do ajuste com os nomes dos campos da API (ADR-0032 §2); roda depois da autorização. */
    private static Change editing(Edit edit) {
        return (current, workspace, now) -> {
            if (edit.expectedAmount() == null && edit.expectedDay() == null) {
                throw new InvalidValueException("expectedAmount", "REQUIRED");
            }
            if (edit.currency() != null && !edit.currency().equals(workspace.baseCurrency().getCurrencyCode())) {
                throw new InvalidValueException("expectedAmount", "CURRENCY_MISMATCH");
            }
            if (edit.expectedAmount() != null) {
                if (edit.expectedAmount().signum() <= 0) {
                    throw new InvalidValueException("expectedAmount", "MUST_BE_POSITIVE");
                }
                if (edit.expectedAmount().stripTrailingZeros().scale() > 2) {
                    throw new InvalidValueException("expectedAmount", "TOO_MANY_DECIMALS");
                }
            }
            if (edit.expectedDay() != null && (edit.expectedDay() < 1 || edit.expectedDay() > 31)) {
                throw new InvalidValueException("expectedDay", "OUT_OF_RANGE");
            }
            Money amount = edit.expectedAmount() == null ? null
                    : Money.of(edit.expectedAmount().toPlainString(), workspace.baseCurrency());
            return current.edit(amount, edit.expectedDay());
        };
    }

    private RecurringSeries apply(UUID workspaceId, SeriesDirection direction, String key, UUID seriesId,
                                  String action, Change change) {
        Workspace workspace = accessGuard.loadAsMember(currentUser.requireCurrentUserId(),
                new WorkspaceId(workspaceId));
        if (direction == null) {
            throw new InvalidValueException("direction", "REQUIRED");
        }
        String seriesKey = key;
        if (seriesId != null) {
            seriesKey = series.findRef(workspace.id(), seriesId)
                    .filter(ref -> ref.direction() == direction)
                    .orElseThrow(RecurringSeriesNotFoundException::new)
                    .key();
        }
        else if (key == null || key.isBlank()) {
            throw new InvalidValueException("key", "REQUIRED");
        }
        Instant now = clock.instant();
        // Primeiro o Workspace (mesma ordem do recálculo), depois a série.
        store.markDueNow(workspace.id(), now);
        RecurringSeries current = series.findForUpdate(workspace.id(), direction, seriesKey)
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
