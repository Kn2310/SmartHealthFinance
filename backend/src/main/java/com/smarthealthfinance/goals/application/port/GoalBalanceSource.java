package com.smarthealthfinance.goals.application.port;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.model.Transaction;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saldo e movimentos de um destino (ADR-0034 §2, §8): o ponto de extensão para os destinos do Open Finance (§9).
 * Lê só fatos, pela semântica de saldo das Transactions, sempre dentro do Workspace.
 */
public interface GoalBalanceSource {

    /** Saldo {@code POSTED} de cada conta até {@code asOf} (inclusive); conta sem lançamento tem saldo zero. */
    Map<AccountId, Money> balances(WorkspaceId workspaceId, Set<AccountId> accounts, LocalDate asOf,
                                   Currency currency);

    /**
     * Transações {@code POSTED} que tocam alguma das contas (origem ou destino) entre as datas (inclusive), mais
     * recentes primeiro (occurredOn desc, createdAt desc, id desc).
     */
    List<Transaction> movements(WorkspaceId workspaceId, Set<AccountId> accounts, LocalDate from, LocalDate to);
}
