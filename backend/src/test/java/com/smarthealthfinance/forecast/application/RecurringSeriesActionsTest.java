package com.smarthealthfinance.forecast.application;

import com.smarthealthfinance.forecast.application.ForecastFakes.FakeStore;
import com.smarthealthfinance.forecast.application.ForecastFakes.InMemorySeries;
import com.smarthealthfinance.forecast.application.exception.RecurringSeriesNotFoundException;
import com.smarthealthfinance.forecast.application.usecase.RecurringSeriesActions;
import com.smarthealthfinance.forecast.application.usecase.RecurringSeriesActions.Edit;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Periodicity;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Confirmar, ignorar e editar (ADR-0030 §2, ADR-0031 §5): autorização por membro, isolamento por Workspace, o
 * Workspace é marcado para agora antes de gravar a série, e a edição não muda o estado nem a elegibilidade.
 */
class RecurringSeriesActionsTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Currency BRL = Currency.getInstance("BRL");
    private static final String RENT = "merchant:aluguel";

    private final FakeStore store = new FakeStore();
    private final InMemorySeries series = new InMemorySeries();
    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    private final User ana = activeUser("sub-ana");
    private final User bob = activeUser("sub-bob");
    private final Workspace anas = personalWorkspace(ana);
    private final Workspace bobs = personalWorkspace(bob);
    private User acting = ana;
    private RecurringSeriesActions actions;

    @BeforeEach
    void setUp() {
        users.store(ana);
        users.store(bob);
        workspaces.store(anas);
        workspaces.store(bobs);
        actions = new RecurringSeriesActions(
                new CurrentUserService(() -> new AuthenticatedIdentity(acting.externalIdentity(),
                        acting.email().value(), null, null), users),
                new WorkspaceAccessGuard(workspaces), series, store, Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry());
        series.put(anas.id(), rent(SeriesStatus.DETECTED));
    }

    private static RecurringSeries rent(SeriesStatus status) {
        return new RecurringSeries(SeriesDirection.EXPENSE, RENT, "ALUGUEL", Channel.account(UUID.randomUUID()),
                Periodicity.MONTHLY, 5, Money.of("1000.00", BRL),
                new Range(Money.of("950.00", BRL), Money.of("1050.00", BRL)), Confidence.MEDIUM,
                List.of(UUID.randomUUID()), "forecast-v1", status, SeriesOrigin.DETECTED, null, null, null);
    }

    private UUID anasId() {
        return anas.id().value();
    }

    @Test
    void confirmAndIgnoreChangeOnlyTheStatusAndMarkTheWorkspaceDueNowBeforeSaving() {
        RecurringSeries confirmed = actions.confirm(anasId(), SeriesDirection.EXPENSE, RENT);

        assertThat(confirmed.status()).isEqualTo(SeriesStatus.CONFIRMED);
        assertThat(confirmed.statusChangedAt()).isEqualTo(NOW);
        assertThat(confirmed.expectedAmount()).isEqualTo(Money.of("1000.00", BRL));
        assertThat(confirmed.origin()).isEqualTo(SeriesOrigin.DETECTED);
        assertThat(store.calls).containsExactly("due-now " + anas.id());
        assertThat(series.calls).containsExactly("lock-series " + RENT, "save " + RENT + " CONFIRMED");

        RecurringSeries ignored = actions.ignore(anasId(), SeriesDirection.EXPENSE, RENT);
        assertThat(ignored.status()).isEqualTo(SeriesStatus.IGNORED);
        assertThat(series.find(anas.id(), SeriesDirection.EXPENSE, RENT).orElseThrow().status())
                .isEqualTo(SeriesStatus.IGNORED);
    }

    @Test
    void repeatingTheSameActionChangesNothing() {
        actions.confirm(anasId(), SeriesDirection.EXPENSE, RENT);
        series.calls.clear();

        RecurringSeries again = actions.confirm(anasId(), SeriesDirection.EXPENSE, RENT);

        assertThat(again.status()).isEqualTo(SeriesStatus.CONFIRMED);
        assertThat(series.calls).containsExactly("lock-series " + RENT);
    }

    @Test
    void editChangesAmountAndDayWithoutChangingTheStatusOrEligibility() {
        RecurringSeries edited = actions.edit(anasId(), SeriesDirection.EXPENSE, RENT,
                new Edit(new BigDecimal("1100.5"), 10));

        assertThat(edited.status()).isEqualTo(SeriesStatus.DETECTED);
        assertThat(edited.statusChangedAt()).isNull();
        assertThat(edited.userExpectedAmount()).isEqualTo(Money.of("1100.50", BRL));
        assertThat(edited.userExpectedDay()).isEqualTo(10);
        // o valor editado vale como exato, mas a coluna detectada (e a confiança detectada) não mudam
        assertThat(edited.effectiveRange()).isEqualTo(Range.point(Money.of("1100.50", BRL)));
        assertThat(edited.effectiveConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(edited.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(edited.expectedAmount()).isEqualTo(Money.of("1000.00", BRL));

        RecurringSeries dayOnly = actions.edit(anasId(), SeriesDirection.EXPENSE, RENT, new Edit(null, 20));
        assertThat(dayOnly.userExpectedAmount()).isEqualTo(Money.of("1100.50", BRL));
        assertThat(dayOnly.userExpectedDay()).isEqualTo(20);
    }

    @Test
    void editValidatesTheDomainRules() {
        assertThatThrownBy(() -> actions.edit(anasId(), SeriesDirection.EXPENSE, RENT, new Edit(null, null)))
                .isInstanceOf(InvalidValueException.class).hasMessageContaining("REQUIRED");
        assertThatThrownBy(() -> actions.edit(anasId(), SeriesDirection.EXPENSE, RENT,
                new Edit(BigDecimal.ZERO, null))).isInstanceOf(InvalidValueException.class)
                .hasMessageContaining("MUST_BE_POSITIVE");
        assertThatThrownBy(() -> actions.edit(anasId(), SeriesDirection.EXPENSE, RENT,
                new Edit(new BigDecimal("-5"), null))).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> actions.edit(anasId(), SeriesDirection.EXPENSE, RENT, new Edit(null, 32)))
                .isInstanceOf(InvalidValueException.class).hasMessageContaining("OUT_OF_RANGE");
        assertThatThrownBy(() -> actions.edit(anasId(), SeriesDirection.EXPENSE, RENT,
                new Edit(new BigDecimal("10.12345"), null))).isInstanceOf(InvalidValueException.class);
        assertThat(series.find(anas.id(), SeriesDirection.EXPENSE, RENT).orElseThrow().userExpectedAmount()).isNull();
    }

    @Test
    void nonMembersGetTheWorkspaceNotFoundAndNothingIsMarkedOrSaved() {
        acting = bob;

        assertThatThrownBy(() -> actions.confirm(anasId(), SeriesDirection.EXPENSE, RENT))
                .isInstanceOf(WorkspaceNotFoundException.class);

        assertThat(store.calls).isEmpty();
        assertThat(series.calls).isEmpty();
        assertThat(series.find(anas.id(), SeriesDirection.EXPENSE, RENT).orElseThrow().status())
                .isEqualTo(SeriesStatus.DETECTED);
    }

    @Test
    void aSeriesOfAnotherWorkspaceIsIndistinguishableFromAMissingOne() {
        acting = bob;

        assertThatThrownBy(() -> actions.confirm(bobs.id().value(), SeriesDirection.EXPENSE, RENT))
                .isInstanceOf(RecurringSeriesNotFoundException.class);
        assertThatThrownBy(() -> actions.ignore(bobs.id().value(), SeriesDirection.INCOME, "merchant:x"))
                .isInstanceOf(RecurringSeriesNotFoundException.class);
        assertThat(series.find(anas.id(), SeriesDirection.EXPENSE, RENT).orElseThrow().status())
                .isEqualTo(SeriesStatus.DETECTED);
    }

    // --- pelo id opaco da API (ADR-0032 §2) ---

    private UUID rentId() {
        return InMemorySeries.idOf(anas.id(), SeriesDirection.EXPENSE, RENT);
    }

    @Test
    void actionsByIdResolveTheKeyOnlyAfterAuthorizingAndRespectTheDirection() {
        assertThat(actions.confirm(anasId(), SeriesDirection.EXPENSE, rentId()).status())
                .isEqualTo(SeriesStatus.CONFIRMED);

        // a mesma série pela rota da outra direção é inexistente, e nada é marcado nem gravado
        store.calls.clear();
        series.calls.clear();
        assertThatThrownBy(() -> actions.ignore(anasId(), SeriesDirection.INCOME, rentId()))
                .isInstanceOf(RecurringSeriesNotFoundException.class);
        assertThatThrownBy(() -> actions.ignore(anasId(), SeriesDirection.EXPENSE, UUID.randomUUID()))
                .isInstanceOf(RecurringSeriesNotFoundException.class);
        assertThat(store.calls).isEmpty();
        assertThat(series.calls).isEmpty();

        acting = bob;
        assertThatThrownBy(() -> actions.confirm(anasId(), SeriesDirection.EXPENSE, rentId()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        // o id da Ana dentro do Workspace do Bob é inexistente
        assertThatThrownBy(() -> actions.confirm(bobs.id().value(), SeriesDirection.EXPENSE, rentId()))
                .isInstanceOf(RecurringSeriesNotFoundException.class);
    }

    @Test
    void clearingTheAdjustmentGoesBackToTheDetectedValuesAndIsIdempotent() {
        actions.edit(anasId(), SeriesDirection.EXPENSE, rentId(), new Edit(new BigDecimal("1100.50"), 10));
        actions.confirm(anasId(), SeriesDirection.EXPENSE, rentId());

        RecurringSeries cleared = actions.clearAdjustment(anasId(), SeriesDirection.EXPENSE, rentId());

        assertThat(cleared.userExpectedAmount()).isNull();
        assertThat(cleared.userExpectedDay()).isNull();
        assertThat(cleared.effectiveAmount()).isEqualTo(Money.of("1000.00", BRL));
        assertThat(cleared.effectiveDay()).isEqualTo(5);
        assertThat(cleared.status()).isEqualTo(SeriesStatus.CONFIRMED);

        series.calls.clear();
        assertThat(actions.clearAdjustment(anasId(), SeriesDirection.EXPENSE, rentId())).isEqualTo(cleared);
        assertThat(series.calls).containsExactly("lock-series " + RENT);
    }

    @Test
    void theAdjustmentIsValidatedWithTheApiFieldNames() {
        assertInvalid(new Edit(null, null), "expectedAmount", "REQUIRED");
        assertInvalid(new Edit(new BigDecimal("0"), null), "expectedAmount", "MUST_BE_POSITIVE");
        assertInvalid(new Edit(new BigDecimal("-5.00"), null), "expectedAmount", "MUST_BE_POSITIVE");
        assertInvalid(new Edit(new BigDecimal("10.005"), null), "expectedAmount", "TOO_MANY_DECIMALS");
        assertInvalid(new Edit(null, 0), "expectedDay", "OUT_OF_RANGE");
        assertInvalid(new Edit(null, 32), "expectedDay", "OUT_OF_RANGE");
        assertInvalid(new Edit(new BigDecimal("10.00"), null, "USD"), "expectedAmount", "CURRENCY_MISMATCH");
        // 10.50 com zeros à direita e o dia 31 são válidos
        assertThat(actions.edit(anasId(), SeriesDirection.EXPENSE, rentId(),
                new Edit(new BigDecimal("10.5000"), 31)).userExpectedDay()).isEqualTo(31);
    }

    private void assertInvalid(Edit edit, String field, String reason) {
        assertThatThrownBy(() -> actions.edit(anasId(), SeriesDirection.EXPENSE, rentId(), edit))
                .isInstanceOfSatisfying(InvalidValueException.class, e -> {
                    assertThat(e.field()).isEqualTo(field);
                    assertThat(e.reason()).isEqualTo(reason);
                });
    }
}
