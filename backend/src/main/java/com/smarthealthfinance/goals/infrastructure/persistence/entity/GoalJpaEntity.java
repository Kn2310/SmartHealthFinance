package com.smarthealthfinance.goals.infrastructure.persistence.entity;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.DestinationKind;
import com.smarthealthfinance.goals.domain.enums.GoalOriginKind;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.valueobject.GoalDestination;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.goals.domain.valueobject.GoalName;
import com.smarthealthfinance.goals.domain.valueobject.GoalOrigin;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "goals")
public class GoalJpaEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private GoalType type;

    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    @Column(name = "planned_monthly", nullable = false)
    private BigDecimal plannedMonthly;

    @Column(nullable = false, updatable = false)
    private String currency;

    /** O mês do prazo como o dia 1. */
    @Column
    private LocalDate deadline;

    @Column(name = "source_account_id")
    private UUID sourceAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_kind", nullable = false, updatable = false)
    private DestinationKind destinationKind;

    @Column(name = "destination_account_id")
    private UUID destinationAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_kind", nullable = false, updatable = false)
    private GoalOriginKind originKind;

    @Column(name = "origin_insight_id", updatable = false)
    private UUID originInsightId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalStatus status;

    @Column(name = "tracking_start", nullable = false, updatable = false)
    private LocalDate trackingStart;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_amount")
    private BigDecimal completedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected GoalJpaEntity() {
    }

    public static GoalJpaEntity from(Goal goal) {
        GoalJpaEntity entity = new GoalJpaEntity();
        entity.id = goal.id().value();
        entity.workspaceId = goal.workspaceId().value();
        entity.name = goal.name().value();
        entity.type = goal.type();
        entity.targetAmount = goal.plan().targetAmount().amount();
        entity.plannedMonthly = goal.plan().plannedMonthly().amount();
        entity.currency = goal.plan().targetAmount().currency().getCurrencyCode();
        entity.deadline = goal.plan().deadlineIfAny().map(month -> month.atDay(1)).orElse(null);
        entity.sourceAccountId = goal.sourceAccountId().map(AccountId::value).orElse(null);
        entity.destinationKind = goal.destination().kind();
        entity.destinationAccountId = goal.destination().account().map(AccountId::value).orElse(null);
        entity.originKind = goal.origin().kind();
        entity.originInsightId = goal.origin().insight().orElse(null);
        entity.status = goal.status();
        entity.trackingStart = goal.trackingStart();
        entity.completedAt = goal.completedAt().orElse(null);
        entity.completedAmount = goal.completedAmount().map(Money::amount).orElse(null);
        entity.createdAt = goal.createdAt();
        entity.updatedAt = goal.updatedAt();
        entity.version = goal.version();
        return entity;
    }

    public Goal toDomain() {
        Currency money = Currency.getInstance(currency);
        GoalPlan plan = new GoalPlan(new Money(targetAmount, money), new Money(plannedMonthly, money),
                deadline == null ? null : YearMonth.from(deadline));
        GoalDestination destination = new GoalDestination(destinationKind,
                destinationAccountId == null ? null : new AccountId(destinationAccountId));
        return Goal.restore(new GoalId(id), new WorkspaceId(workspaceId), new GoalName(name), type, plan,
                sourceAccountId == null ? null : new AccountId(sourceAccountId), destination,
                new GoalOrigin(originKind, originInsightId), status, trackingStart, completedAt,
                completedAmount == null ? null : new Money(completedAmount, money), createdAt, updatedAt, version);
    }
}
