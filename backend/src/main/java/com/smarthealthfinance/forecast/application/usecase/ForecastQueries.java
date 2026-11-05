package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.forecast.application.dto.ForecastViews;
import com.smarthealthfinance.forecast.application.dto.ForecastViews.SeriesIds;
import com.smarthealthfinance.forecast.application.dto.ForecastViews.State;
import com.smarthealthfinance.forecast.application.dto.ForecastViews.View;
import com.smarthealthfinance.forecast.application.exception.ForecastCardNotFoundException;
import com.smarthealthfinance.forecast.application.port.ForecastReader;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.domain.enums.ForecastFreshnessState;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.ForecastFreshness;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Leituras do Forecast (ADR-0032 §1). Autoriza o membro antes de validar; só lê o snapshot gravado, sem calcular na
 * requisição. A freshness pode marcar o Workspace (snapshot de um dia anterior), por isso a transação é de escrita.
 * Logs só com ids, visão e estado.
 */
@Service
public class ForecastQueries {

    private static final Logger log = LoggerFactory.getLogger(ForecastQueries.class);

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final GetForecastStatus status;
    private final ForecastReader reader;
    private final RecurringSeriesRepository series;
    private final CreditCardRepository cards;
    private final MeterRegistry meters;

    public ForecastQueries(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, GetForecastStatus status,
                           ForecastReader reader, RecurringSeriesRepository series, CreditCardRepository cards,
                           MeterRegistry meters) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.status = status;
        this.reader = reader;
        this.series = series;
        this.cards = cards;
        this.meters = meters;
    }

    @Transactional
    public View<ForecastViews.Month> month(UUID workspaceId) {
        return serve(authorize(workspaceId), "month",
                (id, result) -> new ForecastViews.Month(result.month(), ids(id)));
    }

    @Transactional
    public View<ForecastViews.Income> nextIncome(UUID workspaceId) {
        return serve(authorize(workspaceId), "next-income", (id, result) -> result.nextIncome() == null ? null
                : new ForecastViews.Income(result.nextIncome(),
                        ids(id).of(SeriesDirection.INCOME, result.nextIncome().key())));
    }

    @Transactional
    public View<ForecastViews.Upcoming> upcoming(UUID workspaceId) {
        return serve(authorize(workspaceId), "upcoming",
                (id, result) -> new ForecastViews.Upcoming(result.upcomingEvents(), ids(id)));
    }

    /** @param cardId texto do filtro; nulo = todos os cartões. Validado só depois da autorização. */
    @Transactional
    public View<ForecastViews.Invoices> invoices(UUID workspaceId, String cardId) {
        WorkspaceId id = authorize(workspaceId);
        UUID card = cardId == null || cardId.isBlank() ? null : parseCard(cardId);
        if (card != null && cards.findById(id, new CardId(card)).isEmpty()) {
            throw new ForecastCardNotFoundException();
        }
        return serve(id, "invoices", (ws, result) -> {
            List<CardInvoiceProjection> invoices = card == null ? result.invoices() : result.invoices().stream()
                    .filter(invoice -> invoice.card().id().equals(card)).toList();
            return new ForecastViews.Invoices(invoices);
        });
    }

    @Transactional
    public View<ForecastViews.CashFlow> cashFlow(UUID workspaceId) {
        return serve(authorize(workspaceId), "cash-flow", (id, result) -> result.cashFlow() == null ? null
                : new ForecastViews.CashFlow(result.cashFlow()));
    }

    private static UUID parseCard(String cardId) {
        try {
            return UUID.fromString(cardId);
        }
        catch (IllegalArgumentException e) {
            throw new InvalidValueException("cardId", "INVALID");
        }
    }

    private WorkspaceId authorize(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return id;
    }

    private <T> View<T> serve(WorkspaceId id, String view, BiFunction<WorkspaceId, ForecastResult, T> body) {
        Timer.Sample sample = Timer.start(meters);
        ForecastFreshness freshness = status.freshness(id);
        ForecastResult result = reader.current(id).orElse(null);
        State state = stateOf(freshness, result);
        View<T> served = result == null
                ? new View<>(state, freshness, ForecastCalculator.ALGORITHM_VERSION, null, null)
                : new View<>(state, freshness, result.algorithmVersion(), result.asOf(), body.apply(id, result));
        sample.stop(Timer.builder("shf.forecast.query").tag("view", view).tag("state", state.name())
                .register(meters));
        log.info("Forecast served workspaceId={} view={} state={}", id, view, state);
        return served;
    }

    private static State stateOf(ForecastFreshness freshness, ForecastResult result) {
        if (result == null) {
            return freshness.state() == ForecastFreshnessState.NOT_BUILT ? State.NOT_BUILT : State.NO_DATA;
        }
        return result.limitations().contains(Limitation.INSUFFICIENT_HISTORY) ? State.INSUFFICIENT_HISTORY
                : State.READY;
    }

    private SeriesIds ids(WorkspaceId id) {
        Map<String, UUID> byKey = new HashMap<>();
        series.findAllRefs(id).forEach(ref -> byKey.put(SeriesIds.keyOf(ref.direction(), ref.key()), ref.id()));
        return new SeriesIds(byKey);
    }
}
