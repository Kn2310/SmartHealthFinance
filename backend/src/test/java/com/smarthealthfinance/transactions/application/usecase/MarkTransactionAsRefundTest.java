package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.importedIncome;
import static com.smarthealthfinance.transactions.TransactionsFixtures.refund;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "Marcar como reembolso" (adendo de 2026-10-14 aos ADR-0005 §6/§8 e ADR-0009 §9). */
class MarkTransactionAsRefundTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final MarkTransactionAsRefund mark = new MarkTransactionAsRefund(ctx.access, ctx.transactions,
            ctx.categorizer, ctx.events, ctx.clock);

    private final Transaction statementRefund = ctx.store(importedIncome(ctx.anasWorkspace.id(), ctx.aurora.id(),
            "129.90", "Estorno compra online - Loja Azul"));
    private final Transaction purchase = ctx.store(expense(ctx.anasWorkspace.id(), ctx.aurora.id(), "129.90",
            "Loja Azul"));

    @Test
    void reversesTheIncomeAndCreatesALinkedRefund() {
        MarkTransactionAsRefund.Result result = execute(statementRefund, purchase.id().value());

        assertThat(result.replayed()).isFalse();
        assertThat(result.refund().type()).isEqualTo(TransactionType.REFUND);
        assertThat(result.refund().refundOfTransactionId()).isEqualTo(purchase.id().value());
        assertThat(result.refund().reclassifiedFromTransactionId()).isEqualTo(statementRefund.id().value());
        assertThat(result.refund().createdAt()).isEqualTo(NOW);

        Transaction income = ctx.stored(statementRefund);
        assertThat(income.status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(income.reclassifiedAsTransactionId()).contains(new TransactionId(result.refund().id()));
        assertThat(ctx.transactions.size()).isEqualTo(3);
    }

    @Test
    void linkIsOptional() {
        assertThat(execute(statementRefund, null).refund().refundOfTransactionId()).isNull();
    }

    @Test
    void publishesReversedAndCreatedWithOnlyIdsAndDates() {
        MarkTransactionAsRefund.Result result = execute(statementRefund, null);

        assertThat(ctx.published).extracting(IntegrationEvent::eventType)
                .containsExactly("transaction.reversed", "transaction.created");
        assertThat(ctx.published).extracting(IntegrationEvent::aggregateId)
                .containsExactly(statementRefund.id().value(), result.refund().id());
        assertThat(ctx.published).allSatisfy(event -> assertThat(event.payload().keySet())
                .containsExactlyInAnyOrder("transactionId", "occurredOn"));
    }

    @Test
    void repeatingTheSameRequestReturnsTheSameRefund() {
        UUID first = execute(statementRefund, purchase.id().value()).refund().id();

        MarkTransactionAsRefund.Result again = execute(statementRefund, purchase.id().value());

        assertThat(again.replayed()).isTrue();
        assertThat(again.refund().id()).isEqualTo(first);
        assertThat(ctx.transactions.size()).isEqualTo(3);
        assertThat(ctx.published).hasSize(2);
    }

    @Test
    void repeatingWithAnotherLinkIsRejected() {
        execute(statementRefund, null);

        assertInvalidValue(() -> execute(statementRefund, purchase.id().value()), "refundOfTransactionId",
                "ALREADY_RECLASSIFIED");
    }

    @Test
    void linkFollowsTheManualRefundRules() {
        ctx.store(refund(ctx.anasWorkspace.id(), ctx.aurora.id(), "100", purchase));

        assertInvalidValue(() -> execute(statementRefund, purchase.id().value()), "amount", "EXCEEDS_REFUNDABLE");
        assertInvalidValue(() -> execute(statementRefund, statementRefund.id().value()), "refundOfTransactionId",
                "NOT_REFUNDABLE");
        assertInvalidValue(() -> execute(statementRefund, UUID.randomUUID()), "refundOfTransactionId", "NOT_FOUND");

        assertThat(ctx.stored(statementRefund).status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(ctx.published).isEmpty();
    }

    @Test
    void manualIncomeIsNotReclassifiable() {
        assertInvalidValue(() -> execute(purchase, null), "transactionId", "NOT_RECLASSIFIABLE");
    }

    @Test
    void archivedAccountBlocksTheNewRefund() {
        Transaction onArchived = ctx.store(importedIncome(ctx.anasWorkspace.id(), ctx.anasArchived.id(), "10",
                "Estorno"));

        assertThatThrownBy(() -> execute(onArchived, null)).isInstanceOf(AccountArchivedException.class);
    }

    /** Receita de outro Workspace é indistinguível de inexistente; não membro nem chega a ela. */
    @Test
    void isolatedByWorkspace() {
        Transaction bobs = ctx.store(importedIncome(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "10", "Estorno"));

        assertThatThrownBy(() -> execute(bobs, null)).isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> mark.execute(ctx.bobsWorkspace.id().value(), bobs.id().value(),
                new MarkTransactionAsRefund.Command(null))).isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.stored(bobs).status()).isEqualTo(TransactionStatus.POSTED);
    }

    private MarkTransactionAsRefund.Result execute(Transaction income, UUID refundOf) {
        return mark.execute(ctx.anasWorkspaceId(), income.id().value(), new MarkTransactionAsRefund.Command(refundOf));
    }
}
