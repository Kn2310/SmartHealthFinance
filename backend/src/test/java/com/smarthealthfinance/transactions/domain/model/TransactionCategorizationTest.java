package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.DINING_OUT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.GROCERIES;
import static com.smarthealthfinance.transactions.TransactionsFixtures.RETIRED;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SALARY;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.openingBalance;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.refund;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static org.assertj.core.api.Assertions.assertThat;

/** Categoria como atributo não financeiro da transação (ADR-0012 §2/§4/§7). */
class TransactionCategorizationTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);
    private static final Instant MUCH_LATER = CREATED_AT.plusSeconds(120);

    private final WorkspaceId workspace = WorkspaceId.generate(CREATED_AT);
    private final AccountId aurora = AccountId.generate(CREATED_AT);
    private final AccountId norte = AccountId.generate(CREATED_AT);

    @Test
    void newTransactionsAreUncategorized() {
        assertThat(expense(workspace, aurora, "86.40", "Bistrô Lume").category()).isEmpty();
    }

    @Test
    void expenseAcceptsExpenseCategory() {
        Transaction lume = expense(workspace, aurora, "86.40", "Bistrô Lume");

        lume.categorize(DINING_OUT, LATER);

        assertThat(lume.category()).contains(new TransactionCategory(DINING_OUT.code(), CategoryNature.EXPENSE));
        assertThat(lume.updatedAt()).isEqualTo(LATER);
        assertThat(lume.amount()).isEqualTo(expense(workspace, aurora, "86.40", "x").amount());
    }

    @Test
    void incomeAcceptsIncomeCategory() {
        Transaction salary = income(workspace, aurora, "6500.00", SEP_22, "Salário");

        salary.categorize(SALARY, LATER);

        assertThat(salary.category().map(TransactionCategory::code)).contains(SALARY.code());
    }

    @Test
    void refundAcceptsExpenseCategory() {
        Transaction original = expense(workspace, aurora, "100.00", "Loja");
        Transaction devolution = refund(workspace, aurora, "40.00", original);

        devolution.categorize(DINING_OUT, LATER);

        assertThat(devolution.category()).isPresent();
    }

    @Test
    void natureMustMatchType() {
        assertInvalidValue(() -> expense(workspace, aurora, "86.40", "Bistrô Lume").categorize(SALARY, LATER),
                "categoryCode", "INCOMPATIBLE_WITH_TYPE");
        assertInvalidValue(() -> income(workspace, aurora, "300.00", SEP_22, "Freelance").categorize(DINING_OUT, LATER),
                "categoryCode", "INCOMPATIBLE_WITH_TYPE");
        assertInvalidValue(() -> refund(workspace, aurora, "10.00", null).categorize(SALARY, LATER),
                "categoryCode", "INCOMPATIBLE_WITH_TYPE");
    }

    /** Transferência nunca é gasto nem receita; ajuste não entra no fluxo (ADR-0006 §9). */
    @Test
    void transferAndAdjustmentNeverHaveCategory() {
        assertInvalidValue(() -> transfer(workspace, aurora, norte, "500.00").categorize(DINING_OUT, LATER),
                "categoryCode", "NOT_ALLOWED");
        assertInvalidValue(() -> openingBalance(workspace, aurora, AdjustmentDirection.INCREASE, "1000.00")
                .categorize(SALARY, LATER), "categoryCode", "NOT_ALLOWED");
    }

    @Test
    void sameCategoryIsIdempotent() {
        Transaction lume = expense(workspace, aurora, "86.40", "Bistrô Lume");
        lume.categorize(DINING_OUT, LATER);

        lume.categorize(DINING_OUT, MUCH_LATER);

        assertThat(lume.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void changesCategory() {
        Transaction lume = expense(workspace, aurora, "86.40", "Bistrô Lume");
        lume.categorize(DINING_OUT, LATER);

        lume.categorize(GROCERIES, MUCH_LATER);

        assertThat(lume.category().map(TransactionCategory::code)).contains(GROCERIES.code());
        assertThat(lume.updatedAt()).isEqualTo(MUCH_LATER);
    }

    @Test
    void inactiveCategoryCannotBeChosenButStaysWhereItWas() {
        Transaction lume = expense(workspace, aurora, "86.40", "Bistrô Lume");
        assertInvalidValue(() -> lume.categorize(RETIRED, LATER), "categoryCode", "INACTIVE");
        assertThat(lume.category()).isEmpty();

        Transaction legacy = Transaction.restore(lume.id(), workspace, TransactionType.EXPENSE, aurora, null, null,
                lume.amount(), lume.occurredOn(), lume.description(), lume.status(), lume.source(), null,
                RETIRED.asTransactionCategory(), CREATED_AT, CREATED_AT, 0);
        legacy.categorize(RETIRED, LATER);

        assertThat(legacy.category()).contains(RETIRED.asTransactionCategory());
        assertThat(legacy.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void clearsCategoryIdempotently() {
        Transaction lume = expense(workspace, aurora, "86.40", "Bistrô Lume");
        lume.clearCategory(LATER);
        assertThat(lume.updatedAt()).isEqualTo(CREATED_AT);

        lume.categorize(DINING_OUT, LATER);
        lume.clearCategory(MUCH_LATER);

        assertThat(lume.category()).isEmpty();
        assertThat(lume.updatedAt()).isEqualTo(MUCH_LATER);
    }

    /** A categoria não muda saldo nem fluxo: vale em qualquer status, como a descrição. */
    @Test
    void worksInAnyStatus() {
        Transaction pending = pendingExpense(workspace, aurora, "50.00", "Farmácia");
        pending.categorize(GROCERIES, LATER);

        Transaction reversed = expense(workspace, aurora, "86.40", "Bistrô Lume");
        reversed.reverse(LATER);
        reversed.categorize(DINING_OUT, MUCH_LATER);

        assertThat(pending.category()).isPresent();
        assertThat(reversed.category()).isPresent();
        assertThat(reversed.balanceEffectOn(aurora)).isEqualTo(Money.zero(reversed.amount().currency()));
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void everyTypeHasADefinedNature(TransactionType type) {
        Optional<CategoryNature> nature = CategoryNature.acceptedBy(type);

        switch (type) {
            case INCOME -> assertThat(nature).contains(CategoryNature.INCOME);
            case EXPENSE, REFUND -> assertThat(nature).contains(CategoryNature.EXPENSE);
            case TRANSFER, ADJUSTMENT -> assertThat(nature).isEmpty();
        }
    }
}
