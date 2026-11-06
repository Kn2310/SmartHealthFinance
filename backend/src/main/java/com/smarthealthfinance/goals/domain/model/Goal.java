package com.smarthealthfinance.goals.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.DestinationKind;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Objetivo financeiro (ADR-0034 §1). Guarda só o plano e o estado: o progresso e os aportes são derivados do saldo e
 * das transações do destino (§2), nunca gravados aqui.
 */
public class Goal {

    /** Nomes dos campos editáveis, usados no evento {@code goal.plan-changed} (sem valores). */
    public static final String FIELD_NAME = "name";
    public static final String FIELD_TARGET = "targetAmount";
    public static final String FIELD_PLANNED = "plannedMonthly";
    public static final String FIELD_DEADLINE = "deadline";
    public static final String FIELD_SOURCE = "sourceAccountId";
    public static final String FIELD_DESTINATION = "destination";

    private final GoalId id;
    private final WorkspaceId workspaceId;
    private final GoalType type;
    private final GoalOrigin origin;
    private final LocalDate trackingStart;
    private final Instant createdAt;
    private final long version;
    private GoalName name;
    private GoalPlan plan;
    private AccountId sourceAccountId;
    private GoalDestination destination;
    private GoalStatus status;
    private Instant completedAt;
    private Money completedAmount;
    private Instant updatedAt;

    private Goal(GoalId id, WorkspaceId workspaceId, GoalName name, GoalType type, GoalPlan plan,
                 AccountId sourceAccountId, GoalDestination destination, GoalOrigin origin, GoalStatus status,
                 LocalDate trackingStart, Instant completedAt, Money completedAmount, Instant createdAt,
                 Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.sourceAccountId = sourceAccountId;
        this.destination = Objects.requireNonNull(destination, "destination");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.status = Objects.requireNonNull(status, "status");
        this.trackingStart = Objects.requireNonNull(trackingStart, "trackingStart");
        this.completedAt = completedAt;
        this.completedAmount = completedAmount;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        if ((completedAt == null) != (completedAmount == null)) {
            throw new IllegalArgumentException("completedAt e completedAmount andam juntos");
        }
    }

    /**
     * @param sourceAccountId opcional: só a conta padrão do "Registrar aporte"
     * @param today           data de negócio da criação: início do histórico de aportes
     */
    public static Goal create(GoalId id, WorkspaceId workspaceId, GoalName name, GoalType type, GoalPlan plan,
                              AccountId sourceAccountId, GoalDestination destination, GoalOrigin origin,
                              LocalDate today, Instant now) {
        Objects.requireNonNull(plan, "plan").requireDeadlineAfter(YearMonth.from(today));
        requireDestinationMatchesType(type, destination);
        requireSourceDiffersFromDestination(sourceAccountId, destination);
        return new Goal(id, workspaceId, name, type, plan, sourceAccountId, destination, origin, GoalStatus.ACTIVE,
                today, null, null, now, now, 0);
    }

    public static Goal restore(GoalId id, WorkspaceId workspaceId, GoalName name, GoalType type, GoalPlan plan,
                               AccountId sourceAccountId, GoalDestination destination, GoalOrigin origin,
                               GoalStatus status, LocalDate trackingStart, Instant completedAt,
                               Money completedAmount, Instant createdAt, Instant updatedAt, long version) {
        return new Goal(id, workspaceId, name, type, plan, sourceAccountId, destination, origin, status,
                trackingStart, completedAt, completedAmount, createdAt, updatedAt, version);
    }

    /**
     * "Editar objetivo": substitui os campos editáveis. Só objetivo ativo ou pausado é editável. O prazo só é
     * validado contra o mês corrente quando muda (um prazo que já passou pode ser mantido).
     *
     * @return os nomes dos campos que mudaram (vazio = nada mudou)
     */
    public Set<String> update(GoalName newName, GoalPlan newPlan, AccountId newSource, GoalDestination newDestination,
                              LocalDate today, Instant now) {
        Objects.requireNonNull(newName, "name");
        Objects.requireNonNull(newPlan, "plan");
        Objects.requireNonNull(newDestination, "destination");
        ensureOpen();
        requireDestinationMatchesType(type, newDestination);
        requireSourceDiffersFromDestination(newSource, newDestination);
        if (!Objects.equals(plan.deadline(), newPlan.deadline())) {
            newPlan.requireDeadlineAfter(YearMonth.from(today));
        }

        Set<String> changed = new LinkedHashSet<>();
        if (!name.equals(newName)) {
            changed.add(FIELD_NAME);
        }
        changed.addAll(planChanges(newPlan));
        if (!Objects.equals(sourceAccountId, newSource)) {
            changed.add(FIELD_SOURCE);
        }
        if (!destination.equals(newDestination)) {
            changed.add(FIELD_DESTINATION);
        }
        if (changed.isEmpty()) {
            return changed;
        }
        name = newName;
        plan = newPlan;
        sourceAccountId = newSource;
        destination = newDestination;
        updatedAt = now;
        return changed;
    }

    /**
     * "Aplicar ao objetivo" (ADR-0034 §5): troca só o aporte planejado e o prazo; o alvo não muda.
     *
     * @return os nomes dos campos que mudaram
     */
    public Set<String> applyPlan(Money newPlannedMonthly, YearMonth newDeadline, LocalDate today, Instant now) {
        ensureOpen();
        GoalPlan newPlan = new GoalPlan(plan.targetAmount(), newPlannedMonthly, newDeadline);
        if (!Objects.equals(plan.deadline(), newDeadline)) {
            newPlan.requireDeadlineAfter(YearMonth.from(today));
        }
        Set<String> changed = planChanges(newPlan);
        if (!changed.isEmpty()) {
            plan = newPlan;
            updatedAt = now;
        }
        return changed;
    }

    /**
     * Ação de status (ADR-0034 §1). Repetir a mesma ação é idempotente; transição inválida lança
     * {@link GoalStatusConflictException}.
     *
     * @param progress o progresso atual, congelado em {@code completedAmount} ao concluir
     * @return true se o status mudou
     */
    public boolean apply(GoalStatusAction action, Money progress, Instant now) {
        Objects.requireNonNull(action, "action");
        GoalStatus target = switch (action) {
            case PAUSE -> requireFrom(GoalStatus.PAUSED, GoalStatus.ACTIVE);
            case RESUME -> requireFrom(GoalStatus.ACTIVE, GoalStatus.PAUSED);
            case COMPLETE -> requireFrom(GoalStatus.COMPLETED, GoalStatus.ACTIVE, GoalStatus.PAUSED);
            case ARCHIVE -> requireFrom(GoalStatus.ARCHIVED, GoalStatus.ACTIVE, GoalStatus.PAUSED,
                    GoalStatus.COMPLETED);
        };
        if (status == target) {
            return false;
        }
        if (target == GoalStatus.COMPLETED) {
            completedAmount = Objects.requireNonNull(progress, "progress");
            completedAt = now;
        }
        status = target;
        updatedAt = now;
        return true;
    }

    /** "Registrar aporte" só em objetivo ativo ou pausado (ADR-0035). */
    public void ensureAcceptsContributions() {
        ensureOpen();
    }

    private GoalStatus requireFrom(GoalStatus target, GoalStatus... allowed) {
        if (status == target) {
            return target;
        }
        for (GoalStatus from : allowed) {
            if (status == from) {
                return target;
            }
        }
        throw new GoalStatusConflictException(id);
    }

    private void ensureOpen() {
        if (!status.isOpen()) {
            throw new GoalStatusConflictException(id);
        }
    }

    private Set<String> planChanges(GoalPlan newPlan) {
        Set<String> changed = new LinkedHashSet<>();
        if (!plan.targetAmount().equals(newPlan.targetAmount())) {
            changed.add(FIELD_TARGET);
        }
        if (!plan.plannedMonthly().equals(newPlan.plannedMonthly())) {
            changed.add(FIELD_PLANNED);
        }
        if (!Objects.equals(plan.deadline(), newPlan.deadline())) {
            changed.add(FIELD_DEADLINE);
        }
        return changed;
    }

    private static void requireDestinationMatchesType(GoalType type, GoalDestination destination) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(destination, "destination");
        if (type.isReserve() && destination.kind() != DestinationKind.RESERVE) {
            throw new InvalidValueException("destinationAccountId", "NOT_ALLOWED");
        }
        if (!type.isReserve() && destination.kind() == DestinationKind.RESERVE) {
            throw new InvalidValueException("destinationAccountId", "REQUIRED");
        }
    }

    private static void requireSourceDiffersFromDestination(AccountId source, GoalDestination destination) {
        if (source != null && destination.account().filter(source::equals).isPresent()) {
            throw new InvalidValueException("sourceAccountId", "SAME_AS_DESTINATION");
        }
    }

    public GoalId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public GoalName name() { return name; }
    public GoalType type() { return type; }
    public GoalPlan plan() { return plan; }
    public Optional<AccountId> sourceAccountId() { return Optional.ofNullable(sourceAccountId); }
    public GoalDestination destination() { return destination; }
    public GoalOrigin origin() { return origin; }
    public GoalStatus status() { return status; }
    public LocalDate trackingStart() { return trackingStart; }
    public Optional<Instant> completedAt() { return Optional.ofNullable(completedAt); }
    public Optional<Money> completedAmount() { return Optional.ofNullable(completedAmount); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
