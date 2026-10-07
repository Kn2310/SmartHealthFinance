package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.transactions.application.CreateCommandBuilder;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.application.CreateCommandBuilder.refundOf;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** "Refund rastreável" (spec 05.13 §5): o reembolso aponta para a despesa original do mesmo Workspace. */
class RefundTransactionTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final CreateTransaction create = new CreateTransaction(ctx.access, ctx.transactions, ctx.idempotency,
            ctx.clock);
    private final Transaction purchase = ctx.store(expense(ctx.anasWorkspace.id(), ctx.aurora.id(), "100.00", "Loja"));

    @Test
    void refundIsLinkedToTheOriginalExpense() {
        TransactionView view = execute(refundOf(purchase, ctx.aurora).amount("30")).transaction();

        assertThat(view.type()).isEqualTo(TransactionType.REFUND);
        assertThat(view.refundOfTransactionId()).isEqualTo(purchase.id().value());
    }

    /** O valor pode voltar para outra conta (ex.: estorno de compra creditado na conta corrente). */
    @Test
    void refundMayLandInAnotherAccount() {
        assertThat(execute(refundOf(purchase, ctx.norte).amount("30")).transaction().accountId())
                .isEqualTo(ctx.norte.id().value());
    }

    @Test
    void cumulativeRefundsCannotExceedTheOriginal() {
        execute(refundOf(purchase, ctx.aurora).amount("60"));

        assertInvalidValue(() -> execute(refundOf(purchase, ctx.aurora).amount("40.01")), "amount",
                "EXCEEDS_REFUNDABLE");

        execute(refundOf(purchase, ctx.aurora).amount("40"));
        assertThat(ctx.transactions.size()).isEqualTo(3);
    }

    @Test
    void cancelledRefundFreesTheRefundableAmount() {
        TransactionView pending = execute(refundOf(purchase, ctx.aurora).amount("100").status("PENDING"))
                .transaction();
        new CancelTransaction(ctx.access, ctx.transactions, ctx.clock).execute(ctx.anasWorkspaceId(), pending.id());

        assertThat(execute(refundOf(purchase, ctx.aurora).amount("100")).replayed()).isFalse();
    }

    @Test
    void onlyPostedExpensesAreRefundable() {
        Transaction salary = ctx.store(income(ctx.anasWorkspace.id(), ctx.aurora.id(), "5000", SEP_22, "Salário"));
        Transaction pending = ctx.store(pendingExpense(ctx.anasWorkspace.id(), ctx.aurora.id(), "50", "Agendada"));

        assertInvalidValue(() -> execute(refundOf(salary, ctx.aurora)), "refundOfTransactionId", "NOT_REFUNDABLE");
        assertInvalidValue(() -> execute(refundOf(pending, ctx.aurora)), "refundOfTransactionId", "NOT_REFUNDABLE");
    }

    /** Transação de outro Workspace é indistinguível de inexistente. */
    @Test
    void originalMustBelongToTheWorkspace() {
        Transaction bobs = ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "100", "Loja do Bob"));

        assertInvalidValue(() -> execute(refundOf(bobs, ctx.aurora)), "refundOfTransactionId", "NOT_FOUND");
        assertInvalidValue(() -> execute(refundOf(purchase, ctx.aurora).refundOf(UUID.randomUUID())),
                "refundOfTransactionId", "NOT_FOUND");
        assertThat(ctx.transactions.size()).isEqualTo(2);
    }

    @Test
    void refundCreationTimestampComesFromTheClock() {
        assertThat(execute(refundOf(purchase, ctx.aurora).amount("1")).transaction().createdAt()).isEqualTo(NOW);
    }

    private CreateTransaction.Result execute(CreateCommandBuilder command) {
        return create.execute(ctx.anasWorkspaceId(), command.build());
    }
}
