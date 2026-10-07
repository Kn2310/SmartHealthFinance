package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.transactions.application.CreateCommandBuilder;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.exception.IdempotencyKeyReusedException;
import org.junit.jupiter.api.Test;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.application.CreateCommandBuilder.expense;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Idempotency-Key + request hash (spec 05.6): retries nunca duplicam um lançamento financeiro. */
class CreateTransactionIdempotencyTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final CreateTransaction create = new CreateTransaction(ctx.access, ctx.transactions, ctx.idempotency,
            ctx.clock);

    @Test
    void retryWithSameKeyAndPayloadReplaysTheOriginal() {
        CreateTransaction.Result first = execute(expense(ctx.aurora).key("k-1"));
        CreateTransaction.Result retry = execute(expense(ctx.aurora).key("k-1"));

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transaction()).isEqualTo(first.transaction());
        assertThat(ctx.transactions.size()).isEqualTo(1);
    }

    /** O hash é calculado sobre os valores normalizados: "86.4" e " 86.40" são a mesma intenção. */
    @Test
    void equivalentPayloadsAreTheSameRequest() {
        execute(expense(ctx.aurora).key("k-1").amount("86.40").description("Bistrô Lume"));

        CreateTransaction.Result retry = execute(expense(ctx.aurora).key("k-1").amount("86.4")
                .description("  Bistrô Lume "));

        assertThat(retry.replayed()).isTrue();
        assertThat(ctx.transactions.size()).isEqualTo(1);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() {
        execute(expense(ctx.aurora).key("k-1").amount("86.40"));

        assertThatThrownBy(() -> execute(expense(ctx.aurora).key("k-1").amount("86.41")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> execute(expense(ctx.norte).key("k-1").amount("86.40")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(ctx.transactions.size()).isEqualTo(1);
    }

    @Test
    void keysAreScopedToTheWorkspace() {
        execute(expense(ctx.aurora).key("k-1"));
        ctx.actAs(ctx.bob);

        CreateTransaction.Result bobs = create.execute(ctx.bobsWorkspace.id().value(),
                expense(ctx.bobsAccount).key("k-1").build());

        assertThat(bobs.replayed()).isFalse();
        assertThat(ctx.transactions.size()).isEqualTo(2);
    }

    @Test
    void rejectedRequestDoesNotConsumeTheKey() {
        assertInvalidValue(() -> execute(expense(ctx.aurora).key("k-1").amount("0")), "amount", "NOT_POSITIVE");

        CreateTransaction.Result result = execute(expense(ctx.aurora).key("k-1").amount("10"));

        assertThat(result.replayed()).isFalse();
        assertThat(ctx.transactions.size()).isEqualTo(1);
    }

    @Test
    void keyIsRequiredAndValidated() {
        assertInvalidValue(() -> execute(expense(ctx.aurora).key(null)), "Idempotency-Key", "REQUIRED");
        assertInvalidValue(() -> execute(expense(ctx.aurora).key("com espaço")), "Idempotency-Key",
                "INVALID_CHARACTERS");
        assertThat(ctx.transactions.size()).isZero();
    }

    private CreateTransaction.Result execute(CreateCommandBuilder command) {
        return create.execute(ctx.anasWorkspaceId(), command.build());
    }
}
