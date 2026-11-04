package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.forecast.application.dto.ForecastViews.RecurringItem;
import com.smarthealthfinance.forecast.application.exception.RecurringSeriesNotFoundException;
import com.smarthealthfinance.forecast.application.port.ForecastReader;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository.SeriesRef;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesEligibility;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Recorrências do Workspace para a API (ADR-0032 §2): lista, detalhe e as ações do usuário. O estado da série (status,
 * valores efetivos) vem do repositório; elegibilidade, "realizada no mês" e data esperada vêm do último snapshot, sem
 * recalcular regra na requisição. Autoriza o membro antes de validar; série inexistente, de outro Workspace ou da outra
 * direção responde como inexistente.
 */
@Service
public class RecurringSeriesService {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final RecurringSeriesRepository series;
    private final ForecastReader reader;
    private final RecurringSeriesActions actions;

    public RecurringSeriesService(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                                  RecurringSeriesRepository series, ForecastReader reader,
                                  RecurringSeriesActions actions) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.series = series;
        this.reader = reader;
        this.actions = actions;
    }

    /** Elegíveis primeiro, depois o maior valor esperado efetivo; empates pelo id (determinístico). */
    @Transactional(readOnly = true)
    public List<RecurringItem> list(UUID workspaceId, SeriesDirection direction, String status) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        SeriesStatus filter = parseStatus(status);
        Map<String, UUID> ids = new HashMap<>();
        for (SeriesRef ref : series.findAllRefs(id)) {
            ids.put(ref.direction() + "|" + ref.key(), ref.id());
        }
        Map<String, SeriesAssessment> assessments = assessments(id);
        return series.findAll(id).stream()
                .filter(s -> s.direction() == direction && (filter == null || s.status() == filter))
                // Série gravada pelo job entre as duas leituras ainda não tem id aqui: entra na próxima listagem.
                .filter(s -> ids.containsKey(s.direction() + "|" + s.key()))
                .map(s -> item(ids.get(s.direction() + "|" + s.key()), s, assessments))
                .sorted(Comparator
                        .comparing((RecurringItem item) -> item.eligibility() != SeriesEligibility.ELIGIBLE)
                        .thenComparing((RecurringItem item) -> item.series().effectiveAmount().amount(),
                                Comparator.reverseOrder())
                        .thenComparing(RecurringItem::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public RecurringItem detail(UUID workspaceId, SeriesDirection direction, UUID seriesId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return load(id, direction, seriesId);
    }

    @Transactional
    public RecurringItem confirm(UUID workspaceId, SeriesDirection direction, UUID seriesId) {
        return present(workspaceId, seriesId, actions.confirm(workspaceId, direction, seriesId));
    }

    @Transactional
    public RecurringItem ignore(UUID workspaceId, SeriesDirection direction, UUID seriesId) {
        return present(workspaceId, seriesId, actions.ignore(workspaceId, direction, seriesId));
    }

    @Transactional
    public RecurringItem adjust(UUID workspaceId, SeriesDirection direction, UUID seriesId,
                                Supplier<RecurringSeriesActions.Edit> edit) {
        // Autoriza antes de ler o corpo: um não membro recebe 404 mesmo com valor mal formatado (ADR-0003, ADR-0015).
        accessGuard.requireMember(currentUser.requireCurrentUserId(), new WorkspaceId(workspaceId));
        return present(workspaceId, seriesId, actions.edit(workspaceId, direction, seriesId, edit.get()));
    }

    @Transactional
    public RecurringItem clearAdjustment(UUID workspaceId, SeriesDirection direction, UUID seriesId) {
        return present(workspaceId, seriesId, actions.clearAdjustment(workspaceId, direction, seriesId));
    }

    private RecurringItem present(UUID workspaceId, UUID seriesId, RecurringSeries updated) {
        return item(seriesId, updated, assessments(new WorkspaceId(workspaceId)));
    }

    private RecurringItem load(WorkspaceId id, SeriesDirection direction, UUID seriesId) {
        SeriesRef ref = series.findRef(id, seriesId).filter(r -> r.direction() == direction)
                .orElseThrow(RecurringSeriesNotFoundException::new);
        RecurringSeries found = series.find(id, ref.direction(), ref.key())
                .orElseThrow(RecurringSeriesNotFoundException::new);
        return item(ref.id(), found, assessments(id));
    }

    private static RecurringItem item(UUID id, RecurringSeries series, Map<String, SeriesAssessment> assessments) {
        SeriesAssessment assessment = assessments.get(series.direction() + "|" + series.key());
        return assessment == null ? new RecurringItem(id, series, null, null, null)
                : new RecurringItem(id, series, assessment.eligibility(), assessment.realizedThisMonth(),
                        assessment.expectedDate());
    }

    private Map<String, SeriesAssessment> assessments(WorkspaceId id) {
        Map<String, SeriesAssessment> byKey = new HashMap<>();
        reader.current(id).ifPresent(result -> result.series().forEach(
                a -> byKey.put(a.series().direction() + "|" + a.series().key(), a)));
        return byKey;
    }

    private static SeriesStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SeriesStatus.valueOf(status);
        }
        catch (IllegalArgumentException e) {
            throw new InvalidValueException("status", "INVALID");
        }
    }
}
