package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.goals.application.GoalsTestContext;
import com.smarthealthfinance.goals.application.dto.GoalListView;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.exception.GoalDestinationInUseException;
import com.smarthealthfinance.goals.application.exception.GoalNotFoundException;
import com.smarthealthfinance.goals.application.exception.ReserveGoalAlreadyExistsException;
import com.smarthealthfinance.goals.domain.enums.DestinationState;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalStatusAction;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.smarthealthfinance.goals.application.GoalsTestContext.BRL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Casos de uso de objetivos com fakes em memória (ADR-0034, ADR-0035), com os números de 09-dados-de-exemplo. */
class GoalUseCasesTest {

    private final GoalsTestContext ctx = new GoalsTestContext();
    private final Account aurora = ctx.account(ctx.anasWorkspace, "Banco Aurora", AccountType.CHECKING, "6240.35");
    private final Account trip = ctx.account(ctx.anasWorkspace, "Caixinha Lisboa", AccountType.PAYMENT, "4300.00");
    private final Account norte = ctx.account(ctx.anasWorkspace, "Banco Norte", AccountType.SAVINGS, "10000.00");

    @Test
    void createsGoalWithProgressFromTheDestinationAndEmitsCreated() {
        GoalView lisbon = createLisbon();

        assertThat(lisbon.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(lisbon.progress().saved()).isEqualTo(Money.of("4300.00", BRL));
        assertThat(lisbon.progress().percent()).isEqualTo(36);
        assertThat(lisbon.progress().remaining()).isEqualTo(Money.of("7700.00", BRL));
        assertThat(lisbon.trackingStart()).hasToString("2026-09-22");
        assertThat(ctx.outbox).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("goal.created");
            assertThat(event.payload()).containsOnlyKeys("goalId", "type", "origin");
        });
    }

    @Test
    void reserveMirrorsTheSavingsAccountsAndIsUnique() {
        GoalView reserve = createReserve();

        assertThat(reserve.progress().saved()).isEqualTo(Money.of("10000.00", BRL));
        assertThat(reserve.progress().percent()).isEqualTo(40);
        assertThat(reserve.destinationState()).isEqualTo(DestinationState.OK);
        assertThatThrownBy(this::createReserve).isInstanceOf(ReserveGoalAlreadyExistsException.class);
    }

    @Test
    void savingsCannotBeTheDestinationOfAnotherGoal() {
        assertThatThrownBy(() -> create("Outro", norte.id().value()))
                .isInstanceOfSatisfying(InvalidValueException.class,
                        e -> assertThat(e.reason()).isEqualTo("SAVINGS_NOT_ALLOWED"));
        assertThat(ctx.goals.size()).isZero();
    }

    @Test
    void destinationIsExclusiveUntilTheGoalIsClosed() {
        GoalView lisbon = createLisbon();
        assertThatThrownBy(() -> create("Outro", trip.id().value())).isInstanceOf(GoalDestinationInUseException.class);

        ctx.changeStatus.execute(ctx.anasWorkspaceId(), lisbon.id(), GoalStatusAction.COMPLETE);
        assertThat(create("Outro", trip.id().value()).status()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void completingFreezesTheCurrentProgress() {
        GoalView lisbon = createLisbon();

        GoalView done = ctx.changeStatus.execute(ctx.anasWorkspaceId(), lisbon.id(), GoalStatusAction.COMPLETE);
        ctx.balances.set(trip.id(), "0.00");

        assertThat(done.completedAmount()).isEqualTo(Money.of("4300.00", BRL));
        assertThat(ctx.getGoal.execute(ctx.anasWorkspaceId(), lisbon.id()).progress().saved())
                .isEqualTo(Money.of("4300.00", BRL));
    }

    @Test
    void applyPlanChangesOnlyThePlanAndRepeatingDoesNotEmit() {
        GoalView lisbon = createLisbon();
        ctx.outbox.clear();

        GoalView applied = ctx.applyPlan.execute(ctx.anasWorkspaceId(), lisbon.id(),
                new ApplyGoalPlan.Command("780.00", "BRL", "2027-07"));
        ctx.applyPlan.execute(ctx.anasWorkspaceId(), lisbon.id(), new ApplyGoalPlan.Command("780.00", "BRL", "2027-07"));

        assertThat(applied.plannedMonthly()).isEqualTo(Money.of("780.00", BRL));
        assertThat(applied.targetAmount()).isEqualTo(Money.of("12000.00", BRL));
        assertThat(ctx.outbox).singleElement()
                .satisfies(event -> assertThat(event.eventType()).isEqualTo("goal.plan-changed"));
    }

    @Test
    void updateKeepsAnUnchangedArchivedDestination() {
        GoalView lisbon = createLisbon();
        trip.archive(GoalsTestContext.NOW);
        ctx.accounts.store(trip);

        GoalView updated = ctx.updateGoal.execute(ctx.anasWorkspaceId(), lisbon.id(), new UpdateGoal.Command(
                "Lisboa e Porto", "12000.00", "BRL", "700.00", "BRL", "2027-07", aurora.id().value(),
                trip.id().value()));

        assertThat(updated.name()).isEqualTo("Lisboa e Porto");
        assertThat(updated.destinationState()).isEqualTo(DestinationState.ACCOUNT_ARCHIVED);
    }

    @Test
    void listSummarizesOpenGoalsNewestFirst() {
        createReserve();
        GoalView lisbon = createLisbon();

        GoalListView list = ctx.listGoals.execute(ctx.anasWorkspaceId(), null);

        assertThat(list.goals()).extracting(GoalView::id).first().isEqualTo(lisbon.id());
        assertThat(list.savedTotal()).isEqualTo(Money.of("14300.00", BRL));
        assertThat(list.plannedMonthlyTotal()).isEqualTo(Money.of("1500.00", BRL));
        assertThat(list.activeCount()).isEqualTo(2);
    }

    @Test
    void otherWorkspacesAreInvisible() {
        GoalView lisbon = createLisbon();
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> ctx.getGoal.execute(ctx.anasWorkspaceId(), lisbon.id()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> ctx.getGoal.execute(ctx.bobsWorkspace.id().value(), lisbon.id()))
                .isInstanceOf(GoalNotFoundException.class);
        assertThatThrownBy(() -> ctx.getGoal.execute(ctx.bobsWorkspace.id().value(), UUID.randomUUID()))
                .isInstanceOf(GoalNotFoundException.class);
    }

    private GoalView createLisbon() {
        return ctx.createGoal.execute(ctx.anasWorkspaceId(), new CreateGoal.Command("Viagem para Lisboa", "PURCHASE",
                "12000.00", "BRL", "700.00", "BRL", "2027-07", aurora.id().value(), trip.id().value(), null, null));
    }

    private GoalView createReserve() {
        return ctx.createGoal.execute(ctx.anasWorkspaceId(), new CreateGoal.Command("Reserva de emergência",
                "EMERGENCY_RESERVE", "25152.00", "BRL", "800.00", "BRL", null, null, null, "HEALTH", null));
    }

    private GoalView create(String name, UUID destination) {
        return ctx.createGoal.execute(ctx.anasWorkspaceId(), new CreateGoal.Command(name, "CUSTOM", "1000.00", "BRL",
                "100.00", "BRL", null, null, destination, null, null));
    }
}
