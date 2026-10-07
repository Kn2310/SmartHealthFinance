package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.identity.application.exception.UserNotProvisionedException;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.transactions.application.CreateCommandBuilder;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.application.CreateCommandBuilder.expense;
import static com.smarthealthfinance.transactions.application.CreateCommandBuilder.transfer;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateTransactionTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final CreateTransaction create = new CreateTransaction(ctx.access, ctx.transactions, ctx.idempotency,
            ctx.clock);

    @Test
    void recordsPostedManualExpenseByDefault() {
        CreateTransaction.Result result = create.execute(ctx.anasWorkspaceId(),
                expense(ctx.aurora).description("  Bistrô Lume ").amount("86.4").build());

        TransactionView view = result.transaction();
        assertThat(result.replayed()).isFalse();
        assertThat(view.id().version()).isEqualTo(7);
        assertThat(view.workspaceId()).isEqualTo(ctx.anasWorkspaceId());
        assertThat(view.accountId()).isEqualTo(id(ctx.aurora));
        assertThat(view.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(view.amount()).isEqualByComparingTo("86.40");
        assertThat(view.currency()).isEqualTo("BRL");
        assertThat(view.occurredOn()).isEqualTo(SEP_22);
        assertThat(view.description()).isEqualTo("Bistrô Lume");
        assertThat(view.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(view.source()).isEqualTo(TransactionSource.MANUAL);
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(ctx.transactions.findById(ctx.anasWorkspace.id(), new TransactionId(view.id()))).isPresent();
    }

    @Test
    void recordsPendingIncome() {
        TransactionView view = create.execute(ctx.anasWorkspaceId(),
                expense(ctx.aurora).type("INCOME").status("PENDING").description("Salário").build()).transaction();

        assertThat(view.type()).isEqualTo(TransactionType.INCOME);
        assertThat(view.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void recordsTransferBetweenOwnAccounts() {
        TransactionView view = create.execute(ctx.anasWorkspaceId(),
                transfer(ctx.aurora, ctx.norte).amount("500").build()).transaction();

        assertThat(view.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(view.accountId()).isEqualTo(id(ctx.aurora));
        assertThat(view.destinationAccountId()).isEqualTo(id(ctx.norte));
    }

    /** Saldo inicial de conta manual = ADJUSTMENT (ADR-0004 §4), inclusive negativo. */
    @Test
    void recordsOpeningBalanceAsAdjustment() {
        TransactionView view = create.execute(ctx.anasWorkspaceId(), expense(ctx.aurora).type("ADJUSTMENT")
                .direction("DECREASE").amount("350").description("Saldo inicial").build()).transaction();

        assertThat(view.type()).isEqualTo(TransactionType.ADJUSTMENT);
        assertThat(view.adjustmentDirection()).isEqualTo(AdjustmentDirection.DECREASE);
    }

    @Test
    void rejectsInvalidFinancialValuesWithoutPersisting() {
        assertInvalidValue(() -> execute(expense(ctx.aurora).amount("0.00")), "amount", "NOT_POSITIVE");
        assertInvalidValue(() -> execute(expense(ctx.aurora).amount("-86.40")), "amount", "NOT_POSITIVE");
        assertInvalidValue(() -> execute(expense(ctx.aurora).amount("86.405")), "amount", "TOO_MANY_DECIMALS");
        assertInvalidValue(() -> execute(expense(ctx.aurora).amount("8,64")), "amount", "INVALID_FORMAT");
        assertInvalidValue(() -> execute(expense(ctx.aurora).type("PAYMENT")), "type", "INVALID");
        assertInvalidValue(() -> execute(expense(ctx.aurora).status("CANCELLED")), "status", "INVALID_INITIAL");
        assertInvalidValue(() -> execute(expense(ctx.aurora).occurredOn(null)), "occurredOn", "REQUIRED");
        assertInvalidValue(() -> execute(expense(ctx.aurora).description(" ")), "description", "REQUIRED");
        assertInvalidValue(() -> execute(expense(ctx.aurora).account(null)), "accountId", "REQUIRED");

        assertThat(ctx.transactions.size()).isZero();
        assertThat(ctx.idempotency.size()).isZero();
    }

    @Test
    void rejectsTypeSpecificFieldsOnTheWrongType() {
        assertInvalidValue(() -> execute(transfer(ctx.aurora, ctx.aurora)), "destinationAccountId", "SAME_ACCOUNT");
        assertInvalidValue(() -> execute(transfer(ctx.aurora, ctx.norte).destination(null)), "destinationAccountId",
                "REQUIRED");
        assertInvalidValue(() -> execute(expense(ctx.aurora).destination(id(ctx.norte))), "destinationAccountId",
                "NOT_ALLOWED");
        assertInvalidValue(() -> execute(expense(ctx.aurora).type("ADJUSTMENT")), "adjustmentDirection", "REQUIRED");
        assertInvalidValue(() -> execute(expense(ctx.aurora).direction("INCREASE")), "adjustmentDirection",
                "NOT_ALLOWED");
        assertInvalidValue(() -> execute(expense(ctx.aurora).direction("UP").type("ADJUSTMENT")),
                "adjustmentDirection", "INVALID");

        assertThat(ctx.transactions.size()).isZero();
    }

    /** MVP: a moeda da transação é a da conta (BRL, ADR-0003/0004). */
    @Test
    void currencyMustMatchTheAccount() {
        assertInvalidValue(() -> execute(expense(ctx.aurora).currency("USD")), "currency", "MISMATCH");
        assertInvalidValue(() -> execute(expense(ctx.aurora).currency("REAIS")), "currency", "INVALID");
        assertInvalidValue(() -> execute(expense(ctx.aurora).currency(null)), "currency", "REQUIRED");

        assertThat(ctx.transactions.size()).isZero();
    }

    @Test
    void accountMustBelongToTheWorkspace() {
        assertThatThrownBy(() -> execute(expense(ctx.bobsAccount))).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> execute(expense(ctx.aurora).account(UUID.randomUUID())))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> execute(transfer(ctx.aurora, ctx.bobsAccount)))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(ctx.transactions.size()).isZero();
    }

    /** Conta arquivada mantém o histórico, mas não recebe novos lançamentos (nem como destino). */
    @Test
    void archivedAccountsReceiveNoNewTransactions() {
        assertThatThrownBy(() -> execute(expense(ctx.anasArchived))).isInstanceOf(AccountArchivedException.class);
        assertThatThrownBy(() -> execute(transfer(ctx.aurora, ctx.anasArchived)))
                .isInstanceOf(AccountArchivedException.class);

        assertThat(ctx.transactions.size()).isZero();
    }

    @Test
    void nonMemberCannotCreateInAnotherWorkspace() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> execute(expense(ctx.aurora))).isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.transactions.size()).isZero();
    }

    /** Autorização antes da validação: dados inválidos não revelam nada sobre Workspaces alheios. */
    @Test
    void authorizationRunsBeforeValidation() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> execute(expense(ctx.aurora).amount("abc").type("???").key(null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void unknownWorkspaceIsNotFound() {
        assertThatThrownBy(() -> create.execute(UUID.randomUUID(), expense(ctx.aurora).build()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void requiresProvisionedUser() {
        ctx.actAs(activeUser("sub-ghost"));

        assertThatThrownBy(() -> execute(expense(ctx.aurora))).isInstanceOf(UserNotProvisionedException.class);
    }

    private CreateTransaction.Result execute(CreateCommandBuilder command) {
        return create.execute(ctx.anasWorkspaceId(), command.build());
    }
}
