package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.InMemoryCategoryRepository;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.application.service.AutoCategorizer;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.service.SystemCategoryRules;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.DINING_OUT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.GROCERIES;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SALARY;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static com.smarthealthfinance.transactions.application.CreateCommandBuilder.expense;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Categorização automática e aprender com a correção do usuário (ADR-0013). */
class AutoCategorizationTest {

    private static final LocalDate SEP_22 = LocalDate.parse("2026-09-22");

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final WorkspaceId ws = ctx.anasWorkspace.id();

    private final CategorizeTransaction categorize = new CategorizeTransaction(ctx.access, ctx.transactions,
            ctx.categories, ctx.rules, ctx.clock);
    private final CreateTransaction create = new CreateTransaction(ctx.access, ctx.transactions, ctx.idempotency,
            ctx.categorizer, ctx.clock);
    private final AutoCategorizePending pending = new AutoCategorizePending(ctx.access, ctx.transactions,
            ctx.categorizer, ctx.clock);

    private Transaction store(String description) {
        return ctx.store(expense(ws, ctx.aurora.id(), "50.00", description));
    }

    private Transaction stored(Transaction transaction) {
        return ctx.stored(transaction);
    }

    // --- lançamento manual ---

    @Test
    void manualEntryIsCategorizedByTheSystemRuleWithItsVersion() {
        var view = create.execute(ctx.anasWorkspaceId(),
                expense(ctx.aurora).description("Mercado Bom Preço 0712").build()).transaction();

        assertThat(view.categoryCode()).isEqualTo("GROCERIES");
        assertThat(view.categorySource()).isEqualTo(CategorySource.RULE_SYSTEM);
        assertThat(view.merchantName()).isEqualTo("Mercado Bom Preço");
        assertThat(stored(ctx.transactions.findById(ws, new com.smarthealthfinance.transactions.domain.valueobject
                .TransactionId(view.id())).orElseThrow()).categoryOrigin())
                .contains(CategoryOrigin.system(SystemCategoryRules.VERSION));
    }

    @Test
    void manualEntryWithoutAMatchStaysUntreatedButHasAMerchant() {
        var view = create.execute(ctx.anasWorkspaceId(),
                expense(ctx.aurora).description("Pix enviado - Maria Exemplo").build()).transaction();

        assertThat(view.categoryCode()).isNull();
        assertThat(view.categorySource()).isNull();
        assertThat(view.merchantName()).isEqualTo("Maria Exemplo");
    }

    @Test
    void transfersGetNeitherCategoryNorMerchant() {
        var view = create.execute(ctx.anasWorkspaceId(), expense(ctx.aurora).type("TRANSFER")
                .destination(ctx.norte.id().value()).description("Mercado reserva").build()).transaction();

        assertThat(view.categoryCode()).isNull();
        assertThat(view.merchantName()).isNull();
    }

    @Test
    void learnedRuleBeatsTheSystemRuleOnNewEntries() {
        Transaction first = store("Café Grão 01/09");
        categorize.execute(ctx.anasWorkspaceId(), first.id().value(),
                new CategorizeTransaction.Command("GROCERIES", true));

        var view = create.execute(ctx.anasWorkspaceId(),
                expense(ctx.aurora).description("CAFÉ GRÃO 22/09").build()).transaction();

        assertThat(view.categoryCode()).isEqualTo("GROCERIES");
        assertThat(view.categorySource()).isEqualTo(CategorySource.RULE_LEARNED);
    }

    // --- aplicar a transações semelhantes ---

    @Test
    void applyToSimilarLearnsTheRuleAndUpdatesOnlyWhatTheUserHasNotChosen() {
        Transaction chosen = store("Café Grão 01/09");
        Transaction untreated = store("Café Grão 05/09");
        Transaction byRule = store("Café Grão 09/09");
        byRule.categorizeByRule(DINING_OUT, CategoryOrigin.system(SystemCategoryRules.VERSION), ctx.clock.instant());
        ctx.transactions.store(byRule);
        Transaction manual = store("Café Grão 12/09");
        manual.categorize(DINING_OUT, ctx.clock.instant());
        ctx.transactions.store(manual);
        Transaction otherMerchant = store("Padaria Pão Quente");

        categorize.execute(ctx.anasWorkspaceId(), chosen.id().value(),
                new CategorizeTransaction.Command("GROCERIES", true));

        assertThat(stored(chosen).categoryOrigin()).contains(CategoryOrigin.MANUAL);
        assertThat(stored(chosen).category().orElseThrow().code().value()).isEqualTo("GROCERIES");
        assertThat(stored(untreated).category().orElseThrow().code().value()).isEqualTo("GROCERIES");
        assertThat(stored(untreated).categoryOrigin()).contains(CategoryOrigin.LEARNED);
        assertThat(stored(byRule).category().orElseThrow().code().value()).isEqualTo("GROCERIES");
        assertThat(stored(byRule).categoryOrigin()).contains(CategoryOrigin.LEARNED);
        // A escolha manual anterior nunca é sobrescrita.
        assertThat(stored(manual).category().orElseThrow().code().value()).isEqualTo("DINING_OUT");
        assertThat(stored(manual).categoryOrigin()).contains(CategoryOrigin.MANUAL);
        // Outro comerciante não é semelhante.
        assertThat(stored(otherMerchant).category()).isEmpty();
        assertThat(ctx.rules.findAll(ws)).singleElement().satisfies(rule -> {
            assertThat(rule.merchantKey()).isEqualTo("cafe grao");
            assertThat(rule.category().code().value()).isEqualTo("GROCERIES");
        });
    }

    @Test
    void withoutApplyToSimilarNoRuleIsLearned() {
        Transaction chosen = store("Café Grão 01/09");
        Transaction other = store("Café Grão 05/09");

        categorize.execute(ctx.anasWorkspaceId(), chosen.id().value(), new CategorizeTransaction.Command("GROCERIES"));

        assertThat(ctx.rules.findAll(ws)).isEmpty();
        assertThat(stored(other).category()).isEmpty();
    }

    @Test
    void applyToSimilarNeedsACategoryAndAMerchant() {
        Transaction chosen = store("Café Grão 01/09");
        assertInvalidValue(() -> categorize.execute(ctx.anasWorkspaceId(), chosen.id().value(),
                new CategorizeTransaction.Command(null, true)), "applyToSimilar", "REQUIRES_CATEGORY");

        Transaction numeric = ctx.store(expense(ws, ctx.aurora.id(), "10.00", "123456"));
        assertInvalidValue(() -> categorize.execute(ctx.anasWorkspaceId(), numeric.id().value(),
                new CategorizeTransaction.Command("GROCERIES", true)), "applyToSimilar", "NO_MERCHANT");

        assertThat(ctx.rules.findAll(ws)).isEmpty();
    }

    @Test
    void learnedRuleOnlyAppliesToTheNatureItWasLearnedFor() {
        Transaction expense = store("Loja Exemplo");
        Transaction refundLike = ctx.store(income(ws, ctx.aurora.id(), "20.00", SEP_22, "Loja Exemplo"));
        categorize.execute(ctx.anasWorkspaceId(), expense.id().value(),
                new CategorizeTransaction.Command("GROCERIES", true));

        assertThat(stored(refundLike).category()).isEmpty();
        assertThat(ctx.rules.findAll(ws)).singleElement()
                .satisfies(rule -> assertThat(rule.category().nature()).isEqualTo(CategoryNature.EXPENSE));
    }

    @Test
    void learnedRulesAreIsolatedByWorkspace() {
        Transaction anas = store("Café Grão 01/09");
        categorize.execute(ctx.anasWorkspaceId(), anas.id().value(), new CategorizeTransaction.Command("GROCERIES", true));
        Transaction bobs = ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "9.00", "Café Grão 03/09"));

        ctx.actAs(ctx.bob);
        pending.execute(ctx.bobsWorkspace.id().value());

        // Bob não herda a regra da Ana: o café dele cai na regra do sistema.
        assertThat(ctx.transactions.findById(ctx.bobsWorkspace.id(), bobs.id()).orElseThrow().category().orElseThrow()
                .code().value()).isEqualTo("DINING_OUT");
        assertThat(ctx.rules.findAll(ctx.bobsWorkspace.id())).isEmpty();
    }

    // --- categorização retroativa ---

    @Test
    void pendingCategorizesUntreatedAndIdentifiesMerchantsOfLegacyTransactions() {
        Transaction legacy = legacyWithoutMerchant("Posto de Combustível Central");
        ctx.transactions.store(legacy);
        Transaction salary = ctx.store(income(ws, ctx.aurora.id(), "6500.00", SEP_22, "Salário - Empresa Ltda"));
        Transaction unknown = store("Loja Exemplo");
        ctx.store(transfer(ws, ctx.aurora.id(), ctx.norte.id(), "100.00"));

        var result = pending.execute(ctx.anasWorkspaceId());

        // O catálogo do teste tem Mercado, Alimentação fora e Salário: o posto não tem categoria nele.
        assertThat(result.evaluated()).isEqualTo(3);
        assertThat(result.categorized()).isEqualTo(1);
        assertThat(result.merchantsIdentified()).isEqualTo(1);
        assertThat(stored(salary).category().orElseThrow().code().value()).isEqualTo("SALARY");
        assertThat(stored(legacy).merchant().orElseThrow().key()).isEqualTo("posto de combustivel central");
        assertThat(stored(unknown).category()).isEmpty();
    }

    @Test
    void pendingIsIdempotent() {
        store("Mercado Bom Preço");
        pending.execute(ctx.anasWorkspaceId());
        int saves = ctx.transactions.saveCount();

        var again = pending.execute(ctx.anasWorkspaceId());

        assertThat(again.categorized()).isZero();
        assertThat(again.merchantsIdentified()).isZero();
        assertThat(ctx.transactions.saveCount()).isEqualTo(saves);
    }

    @Test
    void pendingNeverTouchesTheUsersChoiceNotEvenNoCategory() {
        Transaction chosen = store("Mercado Bom Preço");
        categorize.execute(ctx.anasWorkspaceId(), chosen.id().value(), new CategorizeTransaction.Command("DINING_OUT"));
        Transaction noCategory = store("Restaurante Sabor");
        categorize.execute(ctx.anasWorkspaceId(), noCategory.id().value(), new CategorizeTransaction.Command(null));

        pending.execute(ctx.anasWorkspaceId());

        assertThat(stored(chosen).category().orElseThrow().code().value()).isEqualTo("DINING_OUT");
        assertThat(stored(noCategory).category()).isEmpty();
        assertThat(stored(noCategory).categoryOrigin()).contains(CategoryOrigin.MANUAL);
    }

    @Test
    void pendingOnlyTouchesTheCallersWorkspace() {
        Transaction bobs = ctx.store(expense(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "9.00", "Mercado Sol"));

        pending.execute(ctx.anasWorkspaceId());

        assertThat(ctx.transactions.findById(ctx.bobsWorkspace.id(), bobs.id()).orElseThrow().category()).isEmpty();
    }

    @Test
    void pendingRequiresMembership() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> pending.execute(ctx.anasWorkspaceId())).isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void pendingProcessesMoreTransactionsThanOneBatch() {
        for (int i = 0; i < 1200; i++) {
            store("Mercado Bom Preço " + (i + 100));
        }

        var result = pending.execute(ctx.anasWorkspaceId());

        assertThat(result.evaluated()).isEqualTo(1200);
        assertThat(result.categorized()).isEqualTo(1200);
    }

    // --- sessão do categorizador ---

    @Test
    void sessionSkipsCategoriesMissingFromTheCatalog() {
        AutoCategorizer limited = new AutoCategorizer(new InMemoryCategoryRepository(List.of(SALARY, GROCERIES)),
                ctx.rules);
        Transaction lume = expense(ws, ctx.aurora.id(), "86.40", "Bistrô Lume");

        assertThat(limited.forWorkspace(ws).apply(lume, ctx.clock.instant())).isFalse();
        assertThat(lume.category()).isEmpty();
    }

    @Test
    void unknownCategoryCodeIsStillRejectedForApplyToSimilar() {
        Transaction chosen = store("Café Grão");

        assertThatThrownBy(() -> categorize.execute(ctx.anasWorkspaceId(), chosen.id().value(),
                new CategorizeTransaction.Command("NOPE", true))).isInstanceOf(InvalidValueException.class);
        assertThat(ctx.rules.findAll(ws)).isEmpty();
    }

    /** Transação gravada antes do ADR-0013: sem comerciante nem origem. */
    private Transaction legacyWithoutMerchant(String description) {
        Transaction base = expense(ws, ctx.aurora.id(), "220.50", description);
        return Transaction.restore(base.id(), ws, base.type(), base.accountId(), null, null, base.amount(),
                base.occurredOn(), base.description(), base.status(), base.source(), null, null, base.createdAt(),
                base.updatedAt(), 0);
    }
}
