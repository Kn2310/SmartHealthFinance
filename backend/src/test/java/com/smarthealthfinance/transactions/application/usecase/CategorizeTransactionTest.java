package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.InMemoryCategoryRepository;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.dto.CategoryView;
import com.smarthealthfinance.transactions.application.dto.TransactionPageView;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CategorizeTransaction, ListCategories e o filtro por categoria da listagem (ADR-0012). */
class CategorizeTransactionTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final InMemoryCategoryRepository categories = new InMemoryCategoryRepository();
    private final CategorizeTransaction categorize = new CategorizeTransaction(ctx.access, ctx.transactions,
            categories, ctx.rules, ctx.clock);
    private final ListCategories listCategories = new ListCategories(ctx.access, categories);
    private final ListTransactions list = new ListTransactions(ctx.access, ctx.transactions);
    private final WorkspaceId ws = ctx.anasWorkspace.id();
    private final Transaction lume = ctx.store(expense(ws, ctx.aurora.id(), "86.40", "BISTRO LUME PIRACICABA"));

    @Test
    void categorizesAndSavesOnce() {
        TransactionView view = execute(lume, "DINING_OUT");

        assertThat(view.categoryCode()).isEqualTo("DINING_OUT");
        assertThat(view.updatedAt()).isEqualTo(NOW);
        assertThat(view.amount()).isEqualByComparingTo("86.40");
        assertThat(ctx.transactions.saveCount()).isEqualTo(1);
        assertThat(ctx.stored(lume).category()).isPresent();
    }

    @Test
    void repeatingTheSameCategoryDoesNotSave() {
        execute(lume, "DINING_OUT");
        execute(lume, "DINING_OUT");

        assertThat(ctx.transactions.saveCount()).isEqualTo(1);
    }

    @Test
    void nullClearsTheCategory() {
        execute(lume, "DINING_OUT");

        TransactionView view = execute(lume, null);

        assertThat(view.categoryCode()).isNull();
        assertThat(ctx.stored(lume).category()).isEmpty();
        assertThat(ctx.transactions.saveCount()).isEqualTo(2);
    }

    @Test
    void clearingAnUntreatedTransactionRecordsTheChoiceOnceAndIsIdempotent() {
        execute(lume, null);
        execute(lume, null);

        // Escolher "Sem categoria" é uma escolha do usuário (ADR-0013 §3): grava uma vez, depois é idempotente.
        assertThat(ctx.transactions.saveCount()).isEqualTo(1);
        assertThat(ctx.stored(lume).categoryOrigin()).contains(CategoryOrigin.MANUAL);
    }

    @Test
    void rejectsUnknownMalformedInactiveAndIncompatibleCategoriesWithoutSaving() {
        Transaction toReserve = ctx.store(transfer(ws, ctx.aurora.id(), ctx.norte.id(), "500"));

        assertInvalidValue(() -> execute(lume, "UNKNOWN"), "categoryCode", "INVALID");
        assertInvalidValue(() -> execute(lume, "dining_out"), "categoryCode", "INVALID");
        assertInvalidValue(() -> execute(lume, "RETIRED"), "categoryCode", "INACTIVE");
        assertInvalidValue(() -> execute(lume, "SALARY"), "categoryCode", "INCOMPATIBLE_WITH_TYPE");
        assertInvalidValue(() -> execute(toReserve, "DINING_OUT"), "categoryCode", "NOT_ALLOWED");
        assertThat(ctx.transactions.saveCount()).isZero();
    }

    @Test
    void transactionOfAnotherWorkspaceIsNotFound() {
        Transaction bobs = ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "10", "Do Bob"));

        assertThatThrownBy(() -> execute(bobs, "DINING_OUT")).isInstanceOf(TransactionNotFoundException.class);
        assertThat(ctx.stored(bobs).category()).isEmpty();
    }

    @Test
    void nonMemberGetsWorkspaceNotFound() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> execute(lume, "DINING_OUT")).isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> listCategories.execute(ctx.anasWorkspaceId()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.transactions.saveCount()).isZero();
    }

    @Test
    void listsTheWholeCatalogInDisplayOrderIncludingInactive() {
        List<CategoryView> catalog = listCategories.execute(ctx.anasWorkspaceId());

        assertThat(catalog).extracting(CategoryView::code)
                .containsExactly("GROCERIES", "DINING_OUT", "SALARY", "RETIRED");
        assertThat(catalog).filteredOn(category -> !category.active()).extracting(CategoryView::code)
                .containsExactly("RETIRED");
        assertThat(catalog.get(2).nature()).isEqualTo(CategoryNature.INCOME);
    }

    @Test
    void filtersByCategoryOrUncategorized() {
        Transaction market = ctx.store(expense(ws, ctx.aurora.id(), "296.08", "Mercado Bom Preço"));
        ctx.store(income(ws, ctx.aurora.id(), "6500", SEP_22, "Salário"));
        execute(lume, "DINING_OUT");
        execute(market, "GROCERIES");

        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), byCategory("DINING_OUT", null))))
                .containsExactly("BISTRO LUME PIRACICABA");
        assertThat(descriptions(list.execute(ctx.anasWorkspaceId(), byCategory(null, true))))
                .containsExactly("Salário");
        assertThat(list.execute(ctx.anasWorkspaceId(), byCategory(null, false)).totalItems()).isEqualTo(3);
        assertThat(list.execute(ctx.anasWorkspaceId(), byCategory("UNKNOWN", null)).totalItems()).isZero();
    }

    @Test
    void rejectsInvalidCategoryFilters() {
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(), byCategory("dining out", null)),
                "categoryCode", "INVALID");
        assertInvalidValue(() -> list.execute(ctx.anasWorkspaceId(), byCategory("DINING_OUT", true)),
                "uncategorized", "CONFLICTS_WITH_CATEGORY");
    }

    private TransactionView execute(Transaction transaction, String categoryCode) {
        return categorize.execute(ctx.anasWorkspaceId(), transaction.id().value(),
                new CategorizeTransaction.Command(categoryCode));
    }

    private static ListTransactions.Query byCategory(String categoryCode, Boolean uncategorized) {
        return new ListTransactions.Query(null, null, null, null, null, null, categoryCode, uncategorized, null, null);
    }

    private static List<String> descriptions(TransactionPageView page) {
        return page.items().stream().map(TransactionView::description).toList();
    }
}
