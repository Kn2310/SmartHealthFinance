package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.application.CreateCommandBuilder.expense;
import static org.assertj.core.api.Assertions.assertThat;

/** Eventos {@code transaction.*} na outbox (ADR-0014 §2): só quando o fato muda, sem dado financeiro no payload. */
class TransactionEventsTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final WorkspaceId ws = ctx.anasWorkspace.id();

    private final CreateTransaction create = new CreateTransaction(ctx.access, ctx.transactions, ctx.idempotency,
            ctx.categorizer, ctx.events, ctx.clock);
    private final PostTransaction post = new PostTransaction(ctx.access, ctx.transactions, ctx.events, ctx.clock);
    private final CancelTransaction cancel = new CancelTransaction(ctx.access, ctx.transactions, ctx.events,
            ctx.clock);
    private final ReverseTransaction reverse = new ReverseTransaction(ctx.access, ctx.transactions, ctx.events,
            ctx.clock);
    private final CategorizeTransaction categorize = new CategorizeTransaction(ctx.access, ctx.transactions,
            ctx.categories, ctx.rules, ctx.events, ctx.clock);
    private final AutoCategorizePending pending = new AutoCategorizePending(ctx.access, ctx.transactions,
            ctx.categorizer, ctx.events, ctx.clock);
    private final UpdateTransaction update = new UpdateTransaction(ctx.access, ctx.transactions, ctx.clock);

    @Test
    void creationPublishesCreatedWithOnlyIdAndBusinessDate() {
        var view = create.execute(ctx.anasWorkspaceId(), expense(ctx.aurora).description("Bistrô Lume")
                .amount("86.40").build()).transaction();

        IntegrationEvent event = single();
        assertThat(event.eventType()).isEqualTo("transaction.created");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.workspaceId()).isEqualTo(ctx.anasWorkspaceId());
        assertThat(event.aggregateType()).isEqualTo("Transaction");
        assertThat(event.aggregateId()).isEqualTo(view.id());
        assertThat(event.payload()).isEqualTo(Map.of("transactionId", view.id().toString(),
                "occurredOn", view.occurredOn().toString()));
    }

    @Test
    void replayedCreationPublishesNothingNew() {
        var command = expense(ctx.aurora).key("k-1").build();
        create.execute(ctx.anasWorkspaceId(), command);
        create.execute(ctx.anasWorkspaceId(), command);

        assertThat(ctx.published).hasSize(1);
    }

    @Test
    void statusTransitionsPublishOnceEach() {
        Transaction toPost = ctx.store(pendingExpense(ws, ctx.aurora.id(), "39.90", "Streamo"));
        Transaction toCancel = ctx.store(pendingExpense(ws, ctx.aurora.id(), "10.00", "Outra"));
        Transaction toReverse = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));

        post.execute(ctx.anasWorkspaceId(), toPost.id().value());
        post.execute(ctx.anasWorkspaceId(), toPost.id().value());
        cancel.execute(ctx.anasWorkspaceId(), toCancel.id().value());
        cancel.execute(ctx.anasWorkspaceId(), toCancel.id().value());
        reverse.execute(ctx.anasWorkspaceId(), toReverse.id().value());
        reverse.execute(ctx.anasWorkspaceId(), toReverse.id().value());

        assertThat(ctx.published).extracting(IntegrationEvent::eventType)
                .containsExactly("transaction.posted", "transaction.cancelled", "transaction.reversed");
        assertThat(ctx.published).extracting(IntegrationEvent::aggregateId)
                .containsExactly(toPost.id().value(), toCancel.id().value(), toReverse.id().value());
    }

    @Test
    void categorizationPublishesOnlyWhenSomethingChanged() {
        Transaction lume = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Loja Alfa"));

        categorize.execute(ctx.anasWorkspaceId(), lume.id().value(), new CategorizeTransaction.Command("GROCERIES"));
        categorize.execute(ctx.anasWorkspaceId(), lume.id().value(), new CategorizeTransaction.Command("GROCERIES"));

        assertThat(single().eventType()).isEqualTo("transaction.categorized");
    }

    @Test
    void applyToSimilarPublishesForEveryTransactionItChanged() {
        Transaction chosen = ctx.store(expense(ws, ctx.aurora.id(), "10.00", "Loja Alfa 01/09"));
        Transaction similar = ctx.store(expense(ws, ctx.aurora.id(), "20.00", "Loja Alfa 02/09"));
        ctx.store(expense(ws, ctx.aurora.id(), "30.00", "Loja Beta"));

        categorize.execute(ctx.anasWorkspaceId(), chosen.id().value(),
                new CategorizeTransaction.Command("GROCERIES", true));

        assertThat(ctx.published).extracting(IntegrationEvent::aggregateId)
                .containsExactlyInAnyOrder(chosen.id().value(), similar.id().value());
        assertThat(ctx.published).extracting(IntegrationEvent::eventType).containsOnly("transaction.categorized");
    }

    @Test
    void pendingCategorizationPublishesOnlyForChangedTransactions() {
        Transaction market = ctx.store(expense(ws, ctx.aurora.id(), "10.00", "Mercado Sol"));
        Transaction legacy = ctx.store(legacyWithoutMerchant(expense(ws, ctx.aurora.id(), "5.00", "Loja Alfa")));
        Transaction untouched = ctx.store(expense(ws, ctx.aurora.id(), "1.00", "Loja Beta"));

        pending.execute(ctx.anasWorkspaceId());
        pending.execute(ctx.anasWorkspaceId());

        // Mercado ganha categoria; Loja Alfa ganha comerciante; Loja Beta já tinha comerciante e não casa com regra.
        assertThat(ctx.published).extracting(IntegrationEvent::aggregateId)
                .containsExactlyInAnyOrder(market.id().value(), legacy.id().value())
                .doesNotContain(untouched.id().value());
    }

    @Test
    void descriptionEditPublishesNothing() {
        Transaction lume = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));

        update.execute(ctx.anasWorkspaceId(), lume.id().value(), new UpdateTransaction.Command("Jantar"));

        assertThat(ctx.published).isEmpty();
    }

    private IntegrationEvent single() {
        assertThat(ctx.published).hasSize(1);
        return ctx.published.getFirst();
    }

    private static Transaction legacyWithoutMerchant(Transaction base) {
        return Transaction.restore(base.id(), base.workspaceId(), base.type(), base.accountId(), null, null,
                base.amount(), base.occurredOn(), base.description(), base.status(), base.source(), null, null,
                base.createdAt(), base.updatedAt(), 0);
    }
}
