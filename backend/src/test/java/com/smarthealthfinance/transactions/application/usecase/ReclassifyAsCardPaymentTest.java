package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.CardReference;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.importedIncome;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "Marcar como pagamento de fatura" (ADR-0025 §7, adendo de 2026-10-23): a conta é a fonte do pagamento. */
class ReclassifyAsCardPaymentTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final UUID AURORA_CARD = UUID.randomUUID();
    private static final UUID OTHER_CARD = UUID.randomUUID();

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final ReclassifyAsCardPayment reclassify = new ReclassifyAsCardPayment(ctx.access, ctx.transactions,
            ctx.events, ctx.clock);

    /** Débito "PAG FATURA" de 05 out, importado do extrato da conta. */
    private final Transaction debit = ctx.store(importedExpense(ctx.aurora.id(), "3012.10", "2026-10-05"));

    @Test
    void reversesTheDebitAndCreatesATransferToTheCard() {
        ReclassifyAsCardPayment.Result result = execute(debit, AURORA_CARD);

        assertThat(result.replayed()).isFalse();
        assertThat(result.matchedExisting()).isFalse();
        assertThat(result.payment().type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(result.payment().accountId()).isEqualTo(ctx.aurora.id().value());
        assertThat(result.payment().destinationCardId()).isEqualTo(AURORA_CARD);
        assertThat(result.payment().amount()).isEqualByComparingTo("3012.10");
        assertThat(result.payment().occurredOn()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(result.payment().source()).isEqualTo(TransactionSource.IMPORT);
        assertThat(result.payment().reclassifiedFromTransactionId()).isEqualTo(debit.id().value());
        assertThat(result.payment().createdAt()).isEqualTo(NOW);

        Transaction expense = ctx.stored(debit);
        assertThat(expense.status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(expense.reclassifiedAsTransactionId()).contains(new TransactionId(result.payment().id()));
        assertThat(ctx.published).extracting(IntegrationEvent::eventType)
                .containsExactly("transaction.reversed", "transaction.created");
    }

    @Test
    void linksToAManualPaymentOfTheSameAccountCardAndAmountWithinFiveDays() {
        Transaction manual = ctx.store(payment(ctx.aurora.id(), AURORA_CARD, "3012.10", "2026-10-02"));

        ReclassifyAsCardPayment.Result result = execute(debit, AURORA_CARD);

        assertThat(result.matchedExisting()).isTrue();
        assertThat(result.payment().id()).isEqualTo(manual.id().value());
        assertThat(ctx.stored(debit).reclassifiedAsTransactionId()).contains(manual.id());
        // Nada novo: o débito em dobro some do saldo.
        assertThat(ctx.transactions.size()).isEqualTo(2);
        assertThat(ctx.published).extracting(IntegrationEvent::eventType).containsExactly("transaction.reversed");
    }

    @Test
    void picksTheClosestManualPaymentThenTheOldest() {
        ctx.store(payment(ctx.aurora.id(), AURORA_CARD, "3012.10", "2026-10-01"));
        Transaction closest = ctx.store(payment(ctx.aurora.id(), AURORA_CARD, "3012.10", "2026-10-03"));
        ctx.store(payment(ctx.aurora.id(), AURORA_CARD, "3012.10", "2026-10-07"));

        assertThat(execute(debit, AURORA_CARD).payment().id()).isEqualTo(closest.id().value());
    }

    @Test
    void ignoresPaymentsThatDoNotMatch() {
        ctx.store(payment(ctx.norte.id(), AURORA_CARD, "3012.10", "2026-10-05"));  // outra conta
        ctx.store(payment(ctx.aurora.id(), OTHER_CARD, "3012.10", "2026-10-05"));  // outro cartão
        ctx.store(payment(ctx.aurora.id(), AURORA_CARD, "3012.00", "2026-10-05")); // outro valor
        ctx.store(payment(ctx.aurora.id(), AURORA_CARD, "3012.10", "2026-09-29")); // 6 dias antes

        assertThat(execute(debit, AURORA_CARD).matchedExisting()).isFalse();
    }

    @Test
    void aManualPaymentAbsorbsOnlyOneDebit() {
        ctx.store(payment(ctx.aurora.id(), AURORA_CARD, "3012.10", "2026-10-05"));
        Transaction secondDebit = ctx.store(importedExpense(ctx.aurora.id(), "3012.10", "2026-10-06"));

        assertThat(execute(debit, AURORA_CARD).matchedExisting()).isTrue();
        assertThat(execute(secondDebit, AURORA_CARD).matchedExisting()).isFalse();
    }

    @Test
    void repeatingWithTheSameCardReturnsTheSamePayment() {
        UUID first = execute(debit, AURORA_CARD).payment().id();

        ReclassifyAsCardPayment.Result again = execute(debit, AURORA_CARD);

        assertThat(again.replayed()).isTrue();
        assertThat(again.payment().id()).isEqualTo(first);
        assertThat(ctx.published).hasSize(2);
    }

    @Test
    void anotherCardIsRejected() {
        execute(debit, AURORA_CARD);

        assertInvalidValue(() -> execute(debit, OTHER_CARD), "transactionId", "ALREADY_RECLASSIFIED");
    }

    @Test
    void onlyAnImportedPostedExpenseIsEligible() {
        Transaction manual = ctx.store(expense(ctx.anasWorkspace.id(), ctx.aurora.id(), "10.00", "Café"));
        Transaction income = ctx.store(importedIncome(ctx.anasWorkspace.id(), ctx.aurora.id(), "10.00", "Pix"));

        assertInvalidValue(() -> execute(manual, AURORA_CARD), "transactionId", "NOT_RECLASSIFIABLE");
        assertInvalidValue(() -> execute(income, AURORA_CARD), "transactionId", "NOT_RECLASSIFIABLE");

        Transaction reversed = ctx.stored(debit);
        reversed.reverse(NOW);
        ctx.transactions.save(reversed);
        assertThatThrownBy(() -> execute(debit, AURORA_CARD))
                .isInstanceOf(InvalidTransactionStatusTransitionException.class);
    }

    @Test
    void aTransactionOfAnotherWorkspaceIsNotFound() {
        Transaction bobs = ctx.store(importedExpense(ctx.bobsAccount.id(), "10.00", "2026-10-05"));

        assertThatThrownBy(() -> execute(bobs, AURORA_CARD)).isInstanceOf(TransactionNotFoundException.class);
    }

    private ReclassifyAsCardPayment.Result execute(Transaction expense, UUID card) {
        return reclassify.execute(ctx.anasWorkspace.id(), expense.id().value(), card);
    }

    private Transaction importedExpense(AccountId account, String amount, String date) {
        return Transaction.createImported(TransactionId.generate(NOW), ctx.accounts.findById(
                        ctx.anasWorkspace.id(), account).map(a -> a.workspaceId()).orElse(ctx.bobsWorkspace.id()),
                account, false, Money.of(amount, BRL), LocalDate.parse(date),
                new TransactionDescription("PAG FATURA AURORA"), NOW);
    }

    private Transaction payment(AccountId account, UUID card, String amount, String date) {
        return Transaction.createCardPayment(TransactionId.generate(NOW), ctx.anasWorkspace.id(), account,
                new CardReference(card), Money.of(amount, BRL), LocalDate.parse(date),
                new TransactionDescription("Pagamento de fatura"), NOW);
    }
}
