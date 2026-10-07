package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.accounts.application.InMemoryAccountRepository;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.model.Transaction;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.accounts.AccountsFixtures.archivedAccount;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;

/**
 * Ana (contas Aurora e Norte, mais uma arquivada) e Bob (conta própria), cada um com seu Workspace pessoal.
 * As chamadas são feitas como Ana até {@link #actAs}.
 */
public final class TransactionsTestContext {

    public static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    public final InMemoryUserRepository users = new InMemoryUserRepository();
    public final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    public final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    public final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
    public final InMemoryTransactionIdempotencyStore idempotency = new InMemoryTransactionIdempotencyStore();
    public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    public final User ana = activeUser("sub-ana");
    public final User bob = activeUser("sub-bob");
    public final Workspace anasWorkspace = personalWorkspace(ana);
    public final Workspace bobsWorkspace = personalWorkspace(bob);

    public final Account aurora = account(anasWorkspace.id(), "Banco Aurora");
    public final Account norte = account(anasWorkspace.id(), "Banco Norte");
    public final Account anasArchived = archivedAccount(anasWorkspace.id(), "Conta antiga");
    public final Account bobsAccount = account(bobsWorkspace.id(), "Conta do Bob");

    private User actingUser = ana;

    public final TransactionAccess access = new TransactionAccess(new CurrentUserService(this::currentIdentity, users),
            new WorkspaceAccessGuard(workspaces), transactions, accounts);

    public TransactionsTestContext() {
        users.store(ana);
        users.store(bob);
        workspaces.store(anasWorkspace);
        workspaces.store(bobsWorkspace);
        accounts.store(aurora);
        accounts.store(norte);
        accounts.store(anasArchived);
        accounts.store(bobsAccount);
    }

    public void actAs(User user) {
        actingUser = user;
    }

    public Transaction store(Transaction transaction) {
        transactions.store(transaction);
        return transaction;
    }

    public Transaction stored(Transaction transaction) {
        return transactions.findById(transaction.workspaceId(), transaction.id()).orElseThrow();
    }

    public UUID anasWorkspaceId() {
        return anasWorkspace.id().value();
    }

    public static UUID id(Account account) {
        return account.id().value();
    }

    private AuthenticatedIdentity currentIdentity() {
        return new AuthenticatedIdentity(actingUser.externalIdentity(), actingUser.email().value(), null, null);
    }
}
