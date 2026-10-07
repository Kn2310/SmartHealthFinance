package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.dto.TransactionPageView;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ListTransactions + GetTransaction. */
class TransactionQueriesTest {

    private static final LocalDate SEP_18 = LocalDate.parse("2026-09-18");
    private static final LocalDate SEP_20 = LocalDate.parse("2026-09-20");
    private static final LocalDate SEP_22 = LocalDate.parse("2026-09-22");

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final ListTransactions list = new ListTransactions(ctx.access, ctx.transactions);
    private final GetTransaction get = new GetTransaction(ctx.access);
    private final WorkspaceId ws = ctx.anasWorkspace.id();

    @Test
    void listsNewestFirstWithTiesBrokenByCreation() {
        ctx.store(expense(ws, ctx.aurora.id(), "24.50", SEP_18, "Café Grão", CREATED_AT));
        ctx.store(expense(ws, ctx.aurora.id(), "86.40", SEP_22, "Bistrô Lume", CREATED_AT));
        ctx.store(expense(ws, ctx.aurora.id(), "296.08", SEP_22, "Mercado Bom Preço", CREATED_AT.plusSeconds(5)));

        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), all())))
                .containsExactly("Mercado Bom Preço", "Bistrô Lume", "Café Grão");
    }

    @Test
    void filtersByPeriodInclusive() {
        ctx.store(expense(ws, ctx.aurora.id(), "24.50", SEP_18, "Café Grão", CREATED_AT));
        ctx.store(expense(ws, ctx.aurora.id(), "220", SEP_20, "Posto Via Norte", CREATED_AT));
        ctx.store(expense(ws, ctx.aurora.id(), "86.40", SEP_22, "Bistrô Lume", CREATED_AT));

        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), query(SEP_18, SEP_20, null, null, null, null))))
                .containsExactly("Posto Via Norte", "Café Grão");
    }

    @Test
    void filtersByTypeStatusAndText() {
        ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));
        ctx.store(pendingExpense(ws, ctx.aurora.id(), "39.90", "Streamo"));
        ctx.store(income(ws, ctx.aurora.id(), "300", SEP_18, "Estúdio Ponto — freelance"));

        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), query(null, null, "INCOME", null, null, null))))
                .containsExactly("Estúdio Ponto — freelance");
        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), query(null, null, "EXPENSE", "PENDING", null, null))))
                .containsExactly("Streamo");
        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), query(null, null, null, null, null, " bistrô "))))
                .containsExactly("Bistrô Lume");
    }

    /** Filtrar por conta inclui as transferências em que ela é o destino. */
    @Test
    void accountFilterMatchesBothSidesOfTransfers() {
        ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));
        ctx.store(transfer(ws, ctx.aurora.id(), ctx.norte.id(), "500"));

        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(),
                query(null, null, null, null, id(ctx.norte), null)))).containsExactly("Transferência para Reserva");
        assertThat(list.execute(ctx.anasWorkspaceId(), query(null, null, null, null, id(ctx.aurora), null))
                .totalItems()).isEqualTo(2);
    }

    @Test
    void paginates() {
        for (int day = 1; day <= 5; day++) {
            ctx.store(expense(ws, ctx.aurora.id(), "10", LocalDate.of(2026, 9, day), "Dia " + day, CREATED_AT));
        }

        TransactionPageView second = list.execute(ctx.anasWorkspaceId(),
                new ListTransactions.Query(null, null, null, null, null, null, 1, 2));

        assertThat(descriptions(second)).containsExactly("Dia 3", "Dia 2");
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.pageSize()).isEqualTo(2);
        assertThat(second.totalItems()).isEqualTo(5);
    }

    @Test
    void defaultsToFirstPageOfFifty() {
        TransactionPageView page = list.execute(ctx.anasWorkspaceId(), all());

        assertThat(page.page()).isZero();
        assertThat(page.pageSize()).isEqualTo(ListTransactions.DEFAULT_PAGE_SIZE).isEqualTo(50);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalItems()).isZero();
    }

    @Test
    void rejectsInvalidQueries() {
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(),
                new ListTransactions.Query(null, null, null, null, null, null, 0, 101)), "pageSize", "OUT_OF_RANGE");
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(),
                new ListTransactions.Query(null, null, null, null, null, null, 0, 0)), "pageSize", "OUT_OF_RANGE");
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(),
                new ListTransactions.Query(null, null, null, null, null, null, -1, 10)), "page", "OUT_OF_RANGE");
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(), query(SEP_22, SEP_18, null, null, null, null)),
                "to", "BEFORE_FROM");
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(), query(null, null, "SPENDING", null, null, null)),
                "type", "INVALID");
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(),
                query(null, null, null, null, null, "x".repeat(ListTransactions.MAX_TEXT_LENGTH + 1))), "q", "TOO_LONG");
    }

    @Test
    void listNeverMixesWorkspaces() {
        ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));
        ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "10", "Do Bob"));

        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), all()))).containsExactly("Bistrô Lume");
    }

    @Test
    void nonMemberCannotList() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> list.execute(ctx.anasWorkspaceId(), all()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void getsTransactionById() {
        Transaction lume = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));

        TransactionView view = get.execute(ctx.anasWorkspaceId(), lume.id().value());

        assertThat(view.description()).isEqualTo("Bistrô Lume");
        assertThat(view.amount()).isEqualByComparingTo("86.40");
    }

    @Test
    void transactionOfAnotherWorkspaceIsNotFound() {
        Transaction bobs = ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "10", "Do Bob"));

        assertThatThrownBy(() -> get.execute(ctx.anasWorkspaceId(), bobs.id().value()))
                .isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> get.execute(ctx.anasWorkspaceId(), UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void nonMemberCannotGet() {
        Transaction lume = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume"));
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> get.execute(ctx.anasWorkspaceId(), lume.id().value()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    private static ListTransactions.Query all() {
        return query(null, null, null, null, null, null);
    }

    private static ListTransactions.Query query(LocalDate from, LocalDate to, String type, String status,
                                                UUID accountId, String text) {
        return new ListTransactions.Query(from, to, type, status, accountId, text, null, null);
    }

    private static java.util.List<String> descriptions(TransactionPageView page) {
        return page.items().stream().map(TransactionView::description).toList();
    }
}
