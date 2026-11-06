package com.smarthealthfinance.goals.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalStatusAction;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.exception.GoalStatusConflictException;
import com.smarthealthfinance.goals.domain.valueobject.GoalDestination;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.goals.domain.valueobject.GoalName;
import com.smarthealthfinance.goals.domain.valueobject.GoalOrigin;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private static final AccountId AURORA = new AccountId(UUID.randomUUID());
    private static final AccountId TRIP = new AccountId(UUID.randomUUID());

    @Test
    void createsAnActiveGoalTrackedFromToday() {
        Goal goal = lisbon();

        assertThat(goal.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(goal.trackingStart()).isEqualTo(TODAY);
        assertThat(goal.version()).isZero();
        assertThat(goal.completedAmount()).isEmpty();
    }

    @Test
    void deadlineMustBeAfterTheCurrentMonth() {
        assertThatThrownBy(() -> create(GoalType.PURCHASE, plan("12000.00", "700.00", YearMonth.of(2026, 9)),
                GoalDestination.account(TRIP), AURORA))
                .isInstanceOfSatisfying(InvalidValueException.class, e -> {
                    assertThat(e.field()).isEqualTo("deadline");
                    assertThat(e.reason()).isEqualTo("PAST");
                });
    }

    @Test
    void reserveUsesTheReserveDestinationAndOthersADedicatedAccount() {
        assertThatThrownBy(() -> create(GoalType.EMERGENCY_RESERVE, plan("25152.00", "800.00", null),
                GoalDestination.account(TRIP), null))
                .isInstanceOfSatisfying(InvalidValueException.class,
                        e -> assertThat(e.reason()).isEqualTo("NOT_ALLOWED"));
        assertThatThrownBy(() -> create(GoalType.PURCHASE, plan("12000.00", "700.00", null),
                GoalDestination.reserve(), null))
                .isInstanceOfSatisfying(InvalidValueException.class,
                        e -> assertThat(e.reason()).isEqualTo("REQUIRED"));

        Goal reserve = create(GoalType.EMERGENCY_RESERVE, plan("25152.00", "800.00", null), GoalDestination.reserve(),
                AURORA);
        assertThat(reserve.destination().account()).isEmpty();
    }

    @Test
    void sourceCannotBeTheDestination() {
        assertThatThrownBy(() -> create(GoalType.PURCHASE, plan("12000.00", "700.00", null),
                GoalDestination.account(TRIP), TRIP))
                .isInstanceOfSatisfying(InvalidValueException.class, e -> {
                    assertThat(e.field()).isEqualTo("sourceAccountId");
                    assertThat(e.reason()).isEqualTo("SAME_AS_DESTINATION");
                });
    }

    @Test
    void planRejectsNonPositiveTargetNegativeContributionOtherCurrencyAndCentFractions() {
        assertThatThrownBy(() -> plan("0.00", "1.00", null)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> plan("10.00", "-1.00", null)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> plan("10.005", "1.00", null)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GoalPlan(Money.of("10.00", Currency.getInstance("USD")), Money.zero(BRL), null))
                .isInstanceOfSatisfying(InvalidValueException.class,
                        e -> assertThat(e.reason()).isEqualTo("UNSUPPORTED"));
        assertThat(plan("10.00", "0.00", null).plannedMonthly()).isEqualTo(Money.zero(BRL));
    }

    @Test
    void updateReportsTheChangedFieldsAndKeepsAPastDeadlineThatDidNotChange() {
        Goal goal = lisbon();
        LocalDate later = LocalDate.of(2027, 8, 10);

        assertThat(goal.update(goal.name(), goal.plan(), AURORA, goal.destination(), later, NOW)).isEmpty();
        assertThat(goal.update(new GoalName("Lisboa e Porto"), plan("13000.00", "700.00", YearMonth.of(2027, 7)),
                null, goal.destination(), later, NOW))
                .containsExactly(Goal.FIELD_NAME, Goal.FIELD_TARGET, Goal.FIELD_SOURCE);
        assertThatThrownBy(() -> goal.update(goal.name(), plan("13000.00", "700.00", YearMonth.of(2027, 8)), null,
                goal.destination(), later, NOW))
                .isInstanceOfSatisfying(InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("deadline"));
    }

    @Test
    void applyPlanChangesOnlyContributionAndDeadline() {
        Goal goal = lisbon();

        assertThat(goal.applyPlan(Money.of("780.00", BRL), YearMonth.of(2027, 7), TODAY, NOW))
                .containsExactly(Goal.FIELD_PLANNED);
        assertThat(goal.plan().targetAmount()).isEqualTo(Money.of("12000.00", BRL));
        assertThat(goal.plan().plannedMonthly()).isEqualTo(Money.of("780.00", BRL));
        assertThat(goal.applyPlan(Money.of("780.00", BRL), YearMonth.of(2027, 7), TODAY, NOW)).isEmpty();
    }

    @Test
    void statusTransitionsAreIdempotentAndCompletionFreezesTheProgress() {
        Goal goal = lisbon();

        assertThat(goal.apply(GoalStatusAction.PAUSE, null, NOW)).isTrue();
        assertThat(goal.apply(GoalStatusAction.PAUSE, null, NOW)).isFalse();
        assertThat(goal.apply(GoalStatusAction.RESUME, null, NOW)).isTrue();
        assertThat(goal.apply(GoalStatusAction.COMPLETE, Money.of("4300.00", BRL), NOW)).isTrue();
        assertThat(goal.completedAmount()).contains(Money.of("4300.00", BRL));
        assertThat(goal.completedAt()).contains(NOW);
        assertThat(goal.apply(GoalStatusAction.COMPLETE, null, NOW)).isFalse();

        assertThatThrownBy(() -> goal.apply(GoalStatusAction.RESUME, null, NOW))
                .isInstanceOf(GoalStatusConflictException.class);
        assertThatThrownBy(goal::ensureAcceptsContributions).isInstanceOf(GoalStatusConflictException.class);
        assertThatThrownBy(() -> goal.applyPlan(Money.of("1.00", BRL), null, TODAY, NOW))
                .isInstanceOf(GoalStatusConflictException.class);

        assertThat(goal.apply(GoalStatusAction.ARCHIVE, null, NOW)).isTrue();
        assertThat(goal.apply(GoalStatusAction.ARCHIVE, null, NOW)).isFalse();
        assertThatThrownBy(() -> goal.apply(GoalStatusAction.PAUSE, null, NOW))
                .isInstanceOf(GoalStatusConflictException.class);
    }

    @Test
    void originInsightIsRequiredOnlyForInsight() {
        assertThatThrownBy(() -> new GoalOrigin(com.smarthealthfinance.goals.domain.enums.GoalOriginKind.INSIGHT, null))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GoalOrigin(com.smarthealthfinance.goals.domain.enums.GoalOriginKind.HEALTH,
                UUID.randomUUID())).isInstanceOf(InvalidValueException.class);
    }

    private static Goal lisbon() {
        return create(GoalType.PURCHASE, plan("12000.00", "700.00", YearMonth.of(2027, 7)),
                GoalDestination.account(TRIP), AURORA);
    }

    private static Goal create(GoalType type, GoalPlan plan, GoalDestination destination, AccountId source) {
        return Goal.create(GoalId.generate(NOW), WORKSPACE, new GoalName("Viagem para Lisboa"), type, plan, source,
                destination, GoalOrigin.manual(), TODAY, NOW);
    }

    private static GoalPlan plan(String target, String monthly, YearMonth deadline) {
        return new GoalPlan(Money.of(target, BRL), Money.of(monthly, BRL), deadline);
    }
}
