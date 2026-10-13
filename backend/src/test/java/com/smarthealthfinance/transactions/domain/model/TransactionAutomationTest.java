package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.service.MerchantNormalizer;
import com.smarthealthfinance.transactions.domain.service.SystemCategoryRules;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.DINING_OUT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.GROCERIES;
import static com.smarthealthfinance.transactions.TransactionsFixtures.RETIRED;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SALARY;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.openingBalance;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Origem da categoria e comerciante (ADR-0013 §1/§3): a escolha do usuário nunca é sobrescrita por regra. */
class TransactionAutomationTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);
    private static final CategoryOrigin SYSTEM = CategoryOrigin.system(SystemCategoryRules.VERSION);

    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final AccountId aurora = new AccountId(UUID.randomUUID());
    private final AccountId norte = new AccountId(UUID.randomUUID());

    private Transaction lume() {
        return expense(workspace, aurora, "86.40", "Bistrô Lume 22/09");
    }

    @Test
    void newTransactionHasMerchantAndAwaitsAutomation() {
        Transaction lume = lume();

        assertThat(lume.merchant()).hasValueSatisfying(merchant -> {
            assertThat(merchant.key()).isEqualTo("bistro lume");
            assertThat(merchant.name()).isEqualTo("Bistrô Lume");
            assertThat(merchant.normalizerVersion()).isEqualTo("v1");
        });
        assertThat(lume.categoryOrigin()).isEmpty();
        assertThat(lume.awaitsAutomation()).isTrue();
    }

    @Test
    void transfersAndAdjustmentsHaveNoMerchantAndNeverAwaitAutomation() {
        Transaction transfer = transfer(workspace, aurora, norte, "100.00");
        Transaction opening = openingBalance(workspace, aurora, AdjustmentDirection.INCREASE, "10.00");

        assertThat(transfer.merchant()).isEmpty();
        assertThat(opening.merchant()).isEmpty();
        assertThat(transfer.awaitsAutomation()).isFalse();
        assertThat(opening.awaitsAutomation()).isFalse();
    }

    @Test
    void ruleCategorizesAndRecordsTheOrigin() {
        Transaction lume = lume();

        assertThat(lume.categorizeByRule(DINING_OUT, SYSTEM, LATER)).isTrue();

        assertThat(lume.category()).contains(DINING_OUT.asTransactionCategory());
        assertThat(lume.categoryOrigin()).contains(SYSTEM);
        assertThat(lume.updatedAt()).isEqualTo(LATER);
        assertThat(lume.awaitsAutomation()).isFalse();
    }

    @Test
    void ruleIsIdempotent() {
        Transaction lume = lume();
        lume.categorizeByRule(DINING_OUT, SYSTEM, LATER);

        assertThat(lume.categorizeByRule(DINING_OUT, SYSTEM, LATER.plusSeconds(60))).isFalse();
        assertThat(lume.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void manualChoiceIsNeverOverwrittenByARule() {
        Transaction lume = lume();
        lume.categorize(GROCERIES, LATER);

        assertThat(lume.categorizeByRule(DINING_OUT, SYSTEM, LATER.plusSeconds(60))).isFalse();
        assertThat(lume.categorizeByRule(DINING_OUT, CategoryOrigin.LEARNED, LATER.plusSeconds(60))).isFalse();

        assertThat(lume.category()).contains(GROCERIES.asTransactionCategory());
        assertThat(lume.categoryOrigin()).contains(CategoryOrigin.MANUAL);
    }

    @Test
    void explicitNoCategoryIsAlsoAManualChoiceRulesCannotUndo() {
        Transaction lume = lume();
        lume.clearCategory(LATER);

        assertThat(lume.awaitsAutomation()).isFalse();
        assertThat(lume.categorizeByRule(DINING_OUT, SYSTEM, LATER.plusSeconds(60))).isFalse();
        assertThat(lume.category()).isEmpty();
    }

    @Test
    void learnedRuleReplacesTheSystemRule() {
        Transaction lume = lume();
        lume.categorizeByRule(DINING_OUT, SYSTEM, LATER);

        assertThat(lume.categorizeByRule(GROCERIES, CategoryOrigin.LEARNED, LATER.plusSeconds(1))).isTrue();
        assertThat(lume.category()).contains(GROCERIES.asTransactionCategory());
        assertThat(lume.categoryOrigin().orElseThrow().source()).isEqualTo(CategorySource.RULE_LEARNED);
        assertThat(lume.categoryOrigin().orElseThrow().ruleVersion()).isNull();
    }

    @Test
    void confirmingARuleChoiceMakesItManual() {
        Transaction lume = lume();
        lume.categorizeByRule(DINING_OUT, SYSTEM, LATER);

        lume.categorize(DINING_OUT, LATER.plusSeconds(1));

        assertThat(lume.categoryOrigin()).contains(CategoryOrigin.MANUAL);
        assertThat(lume.categorizeByRule(GROCERIES, CategoryOrigin.LEARNED, LATER.plusSeconds(2))).isFalse();
    }

    @Test
    void rulesRespectNatureInactivityAndType() {
        Transaction lume = lume();

        assertThat(lume.categorizeByRule(SALARY, SYSTEM, LATER)).isFalse();
        assertThat(lume.categorizeByRule(RETIRED, SYSTEM, LATER)).isFalse();
        assertThat(transfer(workspace, aurora, norte, "100.00").categorizeByRule(DINING_OUT, SYSTEM, LATER)).isFalse();
        assertThat(lume.category()).isEmpty();
    }

    @Test
    void ruleVersionExistsOnlyForSystemRules() {
        assertThatThrownBy(() -> new CategoryOrigin(CategorySource.MANUAL, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CategoryOrigin(CategorySource.RULE_SYSTEM, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiesAMissingMerchantOnlyOnce() {
        Transaction base = lume();
        Transaction legacy = Transaction.restore(base.id(), workspace, base.type(), aurora, null, null,
                base.amount(), base.occurredOn(), base.description(), base.status(), base.source(), null,
                null, CREATED_AT, CREATED_AT, 0);
        assertThat(legacy.merchant()).isEmpty();

        var merchant = MerchantNormalizer.normalize("Bistrô Lume").orElseThrow();
        assertThat(legacy.identifyMerchant(merchant, LATER)).isTrue();
        assertThat(legacy.identifyMerchant(merchant, LATER.plusSeconds(1))).isFalse();
        assertThat(legacy.updatedAt()).isEqualTo(LATER);
    }
}
