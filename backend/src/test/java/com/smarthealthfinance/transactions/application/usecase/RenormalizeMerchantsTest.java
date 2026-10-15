package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository.LearnedRule;
import com.smarthealthfinance.transactions.domain.service.MerchantNormalizer;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import com.smarthealthfinance.transactions.domain.valueobject.Merchant;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static com.smarthealthfinance.transactions.TransactionsFixtures.DINING_OUT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.GROCERIES;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.application.TransactionsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Renormalização dos comerciantes para o normalizador v2 (adendo de 2026-10-14 ao ADR-0013 §1). */
class RenormalizeMerchantsTest {

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final WorkspaceId ws = ctx.anasWorkspace.id();
    private final RenormalizeMerchants renormalize = new RenormalizeMerchants(ctx.transactions, ctx.rules,
            ctx.events, new TransactionTemplate(new NoOpTransactionManager()), ctx.clock);

    @Test
    void recomputesV1MerchantsAndKeepsTheCategory() {
        Transaction bob = ctx.store(legacy("Compra do Bob", "do bob", "Do Bob", DINING_OUT.asTransactionCategory()));

        RenormalizeMerchants.Result result = renormalize.execute();

        Transaction reloaded = ctx.stored(bob);
        assertThat(reloaded.merchant()).contains(new Merchant("bob", "Bob", MerchantNormalizer.VERSION));
        assertThat(reloaded.category()).contains(DINING_OUT.asTransactionCategory());
        assertThat(reloaded.categoryOrigin()).contains(CategoryOrigin.MANUAL);
        assertThat(result).isEqualTo(new RenormalizeMerchants.Result(1, 1, 1));
    }

    /** Mesmo quando a chave não muda, a versão é atualizada (a auditoria sabe qual algoritmo gerou a chave). */
    @Test
    void unchangedKeysStillMoveToTheCurrentVersion() {
        Transaction lume = ctx.store(legacy("Bistrô Lume", "bistro lume", "Bistrô Lume", null));

        assertThat(renormalize.execute()).isEqualTo(new RenormalizeMerchants.Result(1, 1, 0));
        assertThat(ctx.stored(lume).merchant().map(Merchant::normalizerVersion)).contains(MerchantNormalizer.VERSION);
    }

    @Test
    void learnedRuleFollowsTheNewKeyAndTheOldOneIsRemoved() {
        ctx.store(legacy("Compra do Bob", "do bob", "Do Bob", null));
        ctx.rules.upsert(ws, new LearnedRule("do bob", DINING_OUT.asTransactionCategory()), NOW);

        renormalize.execute();

        assertThat(ctx.rules.findAll(ws)).containsExactly(new LearnedRule("bob", DINING_OUT.asTransactionCategory()));
    }

    /** "Do Bob" sem rótulo continua com a chave "do bob": a regra antiga fica para ele. */
    @Test
    void oldRuleStaysWhileAnotherTransactionStillUsesTheKey() {
        ctx.store(legacy("Compra do Bob", "do bob", "Do Bob", null));
        ctx.store(legacy("Do Bob", "do bob", "Do Bob", null));
        ctx.rules.upsert(ws, new LearnedRule("do bob", DINING_OUT.asTransactionCategory()), NOW);

        renormalize.execute();

        assertThat(ctx.rules.findAll(ws)).extracting(LearnedRule::merchantKey).containsExactlyInAnyOrder("do bob", "bob");
    }

    /** As duas chaves com regra: vence a atualizada por último (a intenção mais recente do usuário). */
    @Test
    void newerRuleWinsWhenBothKeysHaveOne() {
        ctx.store(legacy("Compra do Bob", "do bob", "Do Bob", null));
        ctx.rules.upsert(ws, new LearnedRule("bob", GROCERIES.asTransactionCategory()), NOW.minusSeconds(60));
        ctx.rules.upsert(ws, new LearnedRule("do bob", DINING_OUT.asTransactionCategory()), NOW);

        renormalize.execute();

        assertThat(ctx.rules.findAll(ws)).containsExactly(new LearnedRule("bob", DINING_OUT.asTransactionCategory()));
    }

    @Test
    void olderRuleOnTheOldKeyDoesNotOverwrite() {
        ctx.store(legacy("Compra do Bob", "do bob", "Do Bob", null));
        ctx.rules.upsert(ws, new LearnedRule("do bob", DINING_OUT.asTransactionCategory()), NOW.minusSeconds(60));
        ctx.rules.upsert(ws, new LearnedRule("bob", GROCERIES.asTransactionCategory()), NOW);

        renormalize.execute();

        assertThat(ctx.rules.findAll(ws)).containsExactly(new LearnedRule("bob", GROCERIES.asTransactionCategory()));
    }

    @Test
    void publishesCategorizedOnlyForChangedTransactionsAndIsIdempotent() {
        Transaction bob = ctx.store(legacy("Compra do Bob", "do bob", "Do Bob", null));
        ctx.store(expense(ws, ctx.aurora.id(), "10", "Loja Atual"));

        renormalize.execute();
        RenormalizeMerchants.Result again = renormalize.execute();

        assertThat(ctx.published).extracting(IntegrationEvent::eventType).containsExactly("transaction.categorized");
        assertThat(ctx.published).extracting(IntegrationEvent::aggregateId).containsExactly(bob.id().value());
        assertThat(again).isEqualTo(new RenormalizeMerchants.Result(0, 0, 0));
    }

    @Test
    void neverTouchesAnotherWorkspacesRules() {
        ctx.store(legacy("Compra do Bob", "do bob", "Do Bob", null));
        ctx.rules.upsert(ctx.bobsWorkspace.id(), new LearnedRule("do bob", DINING_OUT.asTransactionCategory()), NOW);

        renormalize.execute();

        assertThat(ctx.rules.findAll(ctx.bobsWorkspace.id()))
                .containsExactly(new LearnedRule("do bob", DINING_OUT.asTransactionCategory()));
        assertThat(ctx.rules.findAll(ws)).isEmpty();
    }

    /** Transação gravada pelo normalizador v1 (antes deste adendo). */
    private Transaction legacy(String description, String key, String name,
                               TransactionCategory category) {
        Transaction base = expense(ws, ctx.aurora.id(), "42.00", description);
        return Transaction.restore(base.id(), ws, base.type(), base.accountId(), null, null, base.amount(),
                base.occurredOn(), base.description(), base.status(), base.source(), null, category,
                category == null ? null : CategoryOrigin.MANUAL, new Merchant(key, name, "v1"), base.createdAt(),
                Instant.parse("2026-09-01T00:00:00Z"), 0);
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
