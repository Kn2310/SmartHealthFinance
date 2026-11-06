package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.exception.GoalDestinationInUseException;
import com.smarthealthfinance.goals.application.exception.ReserveGoalAlreadyExistsException;
import com.smarthealthfinance.goals.domain.enums.DestinationKind;
import com.smarthealthfinance.goals.domain.enums.DestinationState;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalDestination;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Regras do destino e da origem (ADR-0034 §2.1, §3): conta dedicada ativa, nunca {@code SAVINGS}, exclusiva entre os
 * objetivos ativos e pausados; a reserva usa as {@code SAVINGS} ativas e é única no Workspace. Também resolve as
 * contas que compõem o destino na leitura do progresso.
 */
@Service
public class GoalDestinations {

    private final AccountRepository accounts;
    private final GoalRepository goals;

    public GoalDestinations(AccountRepository accounts, GoalRepository goals) {
        this.accounts = accounts;
        this.goals = goals;
    }

    /**
     * O destino de um objetivo novo ou editado, validado.
     *
     * @param destinationAccountId obrigatório fora da reserva; proibido na reserva
     * @param self                 o objetivo editado (nulo na criação), que pode manter o próprio destino
     */
    public GoalDestination resolve(WorkspaceId workspace, GoalType type, UUID destinationAccountId, GoalId self) {
        if (type.isReserve()) {
            if (destinationAccountId != null) {
                throw new InvalidValueException("destinationAccountId", "NOT_ALLOWED");
            }
            goals.findOpenReserve(workspace)
                    .filter(other -> !other.id().equals(self))
                    .ifPresent(other -> {
                        throw new ReserveGoalAlreadyExistsException();
                    });
            return GoalDestination.reserve();
        }
        if (destinationAccountId == null) {
            throw new InvalidValueException("destinationAccountId", "REQUIRED");
        }
        Account account = requireActive(workspace, destinationAccountId);
        if (account.type() == AccountType.SAVINGS) {
            throw new InvalidValueException("destinationAccountId", "SAVINGS_NOT_ALLOWED");
        }
        goals.findOpenByDestinationAccount(workspace, account.id())
                .filter(other -> !other.id().equals(self))
                .ifPresent(other -> {
                    throw new GoalDestinationInUseException();
                });
        return GoalDestination.account(account.id());
    }

    /** A conta padrão do "Registrar aporte", opcional: precisa existir no Workspace e estar ativa. */
    public AccountId resolveSource(WorkspaceId workspace, UUID sourceAccountId) {
        if (sourceAccountId == null) {
            return null;
        }
        return requireActive(workspace, sourceAccountId).id();
    }

    /** As contas do destino hoje: a conta dedicada (mesmo arquivada) ou as {@code SAVINGS} ativas da reserva. */
    public Set<AccountId> accountsOf(Goal goal, List<Account> workspaceAccounts) {
        if (goal.destination().kind() == DestinationKind.ACCOUNT) {
            return Set.of(goal.destination().accountId());
        }
        Set<AccountId> reserve = new LinkedHashSet<>();
        for (Account account : workspaceAccounts) {
            if (isReserveAccount(account)) {
                reserve.add(account.id());
            }
        }
        return reserve;
    }

    public DestinationState stateOf(Goal goal, List<Account> workspaceAccounts) {
        if (goal.destination().kind() == DestinationKind.RESERVE) {
            return workspaceAccounts.stream().anyMatch(GoalDestinations::isReserveAccount)
                    ? DestinationState.OK : DestinationState.NO_RESERVE_ACCOUNT;
        }
        AccountId id = goal.destination().accountId();
        return workspaceAccounts.stream()
                .filter(account -> account.id().equals(id))
                .findFirst()
                .map(account -> account.status() == AccountStatus.ACTIVE
                        ? DestinationState.OK : DestinationState.ACCOUNT_ARCHIVED)
                .orElse(DestinationState.ACCOUNT_ARCHIVED);
    }

    public List<Account> workspaceAccounts(WorkspaceId workspace) {
        return accounts.findAllByWorkspace(workspace);
    }

    /** Conta ativa do Workspace: 404 se não existe nele, 409 se arquivada. */
    public Account requireActive(WorkspaceId workspace, UUID accountId) {
        Account account = accounts.findById(workspace, new AccountId(accountId))
                .orElseThrow(AccountNotFoundException::new);
        account.ensureActive();
        return account;
    }

    /** A mesma definição do Reserve do Health (ADR-0017): conta {@code SAVINGS} ativa. */
    public static boolean isReserveAccount(Account account) {
        return account.type() == AccountType.SAVINGS && account.status() == AccountStatus.ACTIVE;
    }
}
