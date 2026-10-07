package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import org.junit.jupiter.api.Test;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateTransactionTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final UpdateTransaction update = new UpdateTransaction(ctx.access, ctx.transactions, ctx.clock);
    private final Transaction lume = ctx.store(expense(ctx.anasWorkspace.id(), ctx.aurora.id(), "86.40",
            "BISTRO LUME PIRACICABA"));

    @Test
    void updatesDescriptionOnly() {
        TransactionView view = update.execute(ctx.anasWorkspaceId(), lume.id().value(),
                new UpdateTransaction.Command(" Bistrô Lume "));

        assertThat(view.description()).isEqualTo("Bistrô Lume");
        assertThat(view.updatedAt()).isEqualTo(NOW);
        assertThat(view.amount()).isEqualByComparingTo("86.40");
        assertThat(ctx.transactions.saveCount()).isEqualTo(1);
        assertThat(ctx.stored(lume).version()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidDescriptionWithoutSaving() {
        assertInvalidValue(() -> update.execute(ctx.anasWorkspaceId(), lume.id().value(),
                new UpdateTransaction.Command("  ")), "description", "REQUIRED");
        assertThat(ctx.transactions.saveCount()).isZero();
    }

    @Test
    void transactionOfAnotherWorkspaceIsNotFound() {
        Transaction bobs = ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "10", "Do Bob"));

        assertThatThrownBy(() -> update.execute(ctx.anasWorkspaceId(), bobs.id().value(),
                new UpdateTransaction.Command("Sequestrada")))
                .isInstanceOf(TransactionNotFoundException.class);
        assertThat(ctx.stored(bobs).description()).isEqualTo(new TransactionDescription("Do Bob"));
    }

    @Test
    void nonMemberGetsWorkspaceNotFound() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> update.execute(ctx.anasWorkspaceId(), lume.id().value(),
                new UpdateTransaction.Command("Sequestrada")))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.transactions.saveCount()).isZero();
    }
}
