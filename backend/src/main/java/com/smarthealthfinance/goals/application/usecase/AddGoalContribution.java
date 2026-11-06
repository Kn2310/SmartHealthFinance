package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalCalendar;
import com.smarthealthfinance.goals.application.service.GoalDestinations;
import com.smarthealthfinance.goals.domain.enums.DestinationKind;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code AddGoalContribution} (05.3, ADR-0034 §2.4, ADR-0035): "Registrar aporte" lança uma {@code TRANSFER}
 * {@code POSTED} da origem para o destino pelo caso de uso de Transactions, com as regras e a idempotência dele
 * ({@code Idempotency-Key} + hash do pedido). Nada é gravado no módulo de objetivos: o aporte é a transferência.
 * <ul>
 *   <li>conta dedicada: o destino é sempre a do objetivo ({@code destinationAccountId}, se vier, tem de ser ela);</li>
 *   <li>reserva: {@code destinationAccountId} obrigatório, uma conta {@code SAVINGS} ativa;</li>
 *   <li>data entre o início do acompanhamento e hoje.</li>
 * </ul>
 */
@Service
public class AddGoalContribution {

    private static final Logger log = LoggerFactory.getLogger(AddGoalContribution.class);
    private static final String DESCRIPTION_PREFIX = "Aporte · ";

    private final GoalAccess access;
    private final GoalDestinations destinations;
    private final GoalCalendar calendar;
    private final CreateTransaction createTransaction;

    public AddGoalContribution(GoalAccess access, GoalDestinations destinations, GoalCalendar calendar,
                               CreateTransaction createTransaction) {
        this.access = access;
        this.destinations = destinations;
        this.calendar = calendar;
        this.createTransaction = createTransaction;
    }

    /** @param description opcional; padrão "Aporte · {nome do objetivo}" */
    public record Command(String idempotencyKey, UUID sourceAccountId, UUID destinationAccountId, String amount,
                          String currency, LocalDate occurredOn, String description) {}

    @Transactional
    public CreateTransaction.Result execute(UUID workspaceId, UUID goalId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Goal goal = access.requireGoal(workspace, goalId);
        goal.ensureAcceptsContributions();

        if (command.sourceAccountId() == null) {
            throw new InvalidValueException("sourceAccountId", "REQUIRED");
        }
        if (command.currency() != null && !GoalPlan.CURRENCY.getCurrencyCode().equals(command.currency().strip())) {
            throw new InvalidValueException("amount.currency", "UNSUPPORTED");
        }
        LocalDate today = calendar.today();
        if (command.occurredOn() == null) {
            throw new InvalidValueException("occurredOn", "REQUIRED");
        }
        if (command.occurredOn().isAfter(today)) {
            throw new InvalidValueException("occurredOn", "FUTURE");
        }
        if (command.occurredOn().isBefore(goal.trackingStart())) {
            throw new InvalidValueException("occurredOn", "BEFORE_TRACKING_START");
        }

        UUID destination = destinationOf(workspace, goal, command.destinationAccountId());
        if (goal.type().isReserve() && GoalDestinations.isReserveAccount(
                destinations.requireActive(workspace, command.sourceAccountId()))) {
            // Entre duas contas SAVINGS o dinheiro não entra na reserva: seria um aporte que não aparece (§2.3).
            throw new InvalidValueException("sourceAccountId", "RESERVE_ACCOUNT");
        }
        CreateTransaction.Result result = createTransaction.execute(workspaceId, new CreateTransaction.Command(
                command.idempotencyKey(), "TRANSFER", command.sourceAccountId(), destination, null, command.amount(),
                command.currency(), command.occurredOn(), description(goal, command.description()), "POSTED", null));

        if (!result.replayed()) {
            log.info("Goal contribution recorded goalId={} workspaceId={} transactionId={}", goal.id(), workspace,
                    result.transaction().id());
        }
        return result;
    }

    private UUID destinationOf(WorkspaceId workspace, Goal goal, UUID requested) {
        if (goal.destination().kind() == DestinationKind.ACCOUNT) {
            UUID own = goal.destination().accountId().value();
            if (requested != null && !requested.equals(own)) {
                throw new InvalidValueException("destinationAccountId", "NOT_ALLOWED");
            }
            return own;
        }
        if (requested == null) {
            throw new InvalidValueException("destinationAccountId", "REQUIRED");
        }
        Account account = destinations.requireActive(workspace, requested);
        if (!GoalDestinations.isReserveAccount(account)) {
            throw new InvalidValueException("destinationAccountId", "NOT_RESERVE_ACCOUNT");
        }
        return account.id().value();
    }

    private static String description(Goal goal, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        String text = DESCRIPTION_PREFIX + goal.name().value();
        return text.length() <= TransactionDescription.MAX_LENGTH ? text
                : text.substring(0, TransactionDescription.MAX_LENGTH);
    }
}
