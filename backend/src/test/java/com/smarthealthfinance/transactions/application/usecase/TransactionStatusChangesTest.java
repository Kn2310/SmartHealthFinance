package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostTransaction + CancelTransaction + ReverseTransaction. */
class TransactionStatusChangesTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final PostTransaction post = new PostTransaction(ctx.access, ctx.transactions, ctx.clock);
    private final CancelTransaction cancel = new CancelTransaction(ctx.access, ctx.transactions, ctx.clock);
    private final ReverseTransaction reverse = new ReverseTransaction(ctx.access, ctx.transactions, ctx.clock);
    private final WorkspaceId ws = ctx.anasWorkspace.id();

    @Test
    void postsPendingTransaction() {
        Transaction pending = ctx.store(pendingExpense(ws, ctx.aurora.id(), "39.90", "Streamo"));

        TransactionView view = post.execute(ctx.anasWorkspaceId(), pending.id().value());

        assertThat(view.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(view.updatedAt()).isEqualTo(NOW);
        assertThat(ctx.stored(pending).status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(ctx.stored(pending).version()).isEqualTo(1);
    }

    @Test
    void cancelsPendingTransaction() {
        Transaction pending = ctx.store(pendingExpense(ws, ctx.aurora.id(), "39.90", "Streamo"));

        assertThat(cancel.execute(ctx.anasWorkspaceId(), pending.id().value()).status())
                .isEqualTo(TransactionStatus.CANCELLED);
    }

    /** Fato lançado não é apagado nem editado: é estornado (auditável). */
    @Test
    void reversesPostedTransactionAndKeepsIt() {
        Transaction posted = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));

        assertThat(reverse.execute(ctx.anasWorkspaceId(), posted.id().value()).status())
                .isEqualTo(TransactionStatus.REVERSED);
        assertThat(ctx.stored(posted).amount()).isEqualTo(posted.amount());
        assertThat(ctx.transactions.size()).isEqualTo(1);
    }

    @Test
    void invalidTransitionsAreRejectedWithoutSaving() {
        Transaction posted = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));
        Transaction pending = ctx.store(pendingExpense(ws, ctx.aurora.id(), "39.90", "Streamo"));

        assertThatThrownBy(() -> cancel.execute(ctx.anasWorkspaceId(), posted.id().value()))
                .isInstanceOf(InvalidTransactionStatusTransitionException.class);
        assertThatThrownBy(() -> reverse.execute(ctx.anasWorkspaceId(), pending.id().value()))
                .isInstanceOf(InvalidTransactionStatusTransitionException.class);
        assertThat(ctx.transactions.saveCount()).isZero();
    }

    @Test
    void repeatedTransitionIsIdempotent() {
        Transaction posted = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));

        reverse.execute(ctx.anasWorkspaceId(), posted.id().value());
        TransactionView again = reverse.execute(ctx.anasWorkspaceId(), posted.id().value());

        assertThat(again.status()).isEqualTo(TransactionStatus.REVERSED);
    }

    /** Lançar efetiva o movimento: exige conta ativa, como na criação. */
    @Test
    void postingRequiresActiveAccount() {
        Transaction pending = ctx.store(pendingExpense(ws, ctx.anasArchived.id(), "39.90", "Streamo"));

        assertThatThrownBy(() -> post.execute(ctx.anasWorkspaceId(), pending.id().value()))
                .isInstanceOf(AccountArchivedException.class);
        assertThat(ctx.stored(pending).status()).isEqualTo(TransactionStatus.PENDING);
    }

    /** Cancelar/estornar remove movimento: permitido mesmo com a conta arquivada. */
    @Test
    void voidingIsAllowedOnArchivedAccounts() {
        Transaction posted = ctx.store(expense(ws, ctx.anasArchived.id(), "86.40", "Antiga"));

        assertThat(reverse.execute(ctx.anasWorkspaceId(), posted.id().value()).status())
                .isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    void transactionOfAnotherWorkspaceIsNotFound() {
        Transaction bobs = ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "10", "Do Bob"));

        assertThatThrownBy(() -> reverse.execute(ctx.anasWorkspaceId(), bobs.id().value()))
                .isInstanceOf(TransactionNotFoundException.class);
        assertThat(ctx.stored(bobs).status()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void nonMemberCannotChangeStatus() {
        Transaction pending = ctx.store(pendingExpense(ws, ctx.aurora.id(), "39.90", "Streamo"));
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> post.execute(ctx.anasWorkspaceId(), pending.id().value()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> cancel.execute(ctx.anasWorkspaceId(), pending.id().value()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.transactions.saveCount()).isZero();
    }
}
