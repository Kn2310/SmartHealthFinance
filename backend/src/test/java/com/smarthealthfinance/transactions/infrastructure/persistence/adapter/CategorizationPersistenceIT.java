package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository.LearnedRule;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.service.SystemCategoryRules;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Comerciante, origem da categoria e regras aprendidas no banco (ADR-0013 §8/§13). */
class CategorizationPersistenceIT extends IntegrationTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);

    @Autowired
    CategoryRepository categories;

    @Autowired
    CategoryRuleRepository rules;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    UserRepository users;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private WorkspaceId anas;
    private WorkspaceId bobs;
    private AccountId aurora;
    private AccountId norte;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        anas = persistedWorkspace(activeUser("sub-ana"));
        bobs = persistedWorkspace(activeUser("sub-bob"));
        aurora = persistedAccount(anas, "Banco Aurora");
        norte = persistedAccount(anas, "Banco Norte");
    }

    @Test
    void merchantAndOriginSurviveARoundTrip() {
        Transaction lume = expense(anas, aurora, "86.40", "Bistrô Lume 22/09");
        transactions.add(lume);

        Transaction loaded = transactions.findById(anas, lume.id()).orElseThrow();
        assertThat(loaded.merchant().orElseThrow().key()).isEqualTo("bistro lume");
        assertThat(loaded.merchant().orElseThrow().name()).isEqualTo("Bistrô Lume");
        assertThat(loaded.categoryOrigin()).isEmpty();

        loaded.categorizeByRule(category("DINING_OUT"), CategoryOrigin.system(SystemCategoryRules.VERSION), LATER);
        transactions.save(loaded);

        Transaction reloaded = transactions.findById(anas, lume.id()).orElseThrow();
        assertThat(reloaded.category().orElseThrow().code().value()).isEqualTo("DINING_OUT");
        assertThat(reloaded.categoryOrigin()).contains(CategoryOrigin.system("system-v1"));
        assertThat(jdbc.queryForObject("select category_rule_version from transactions", String.class))
                .isEqualTo("system-v1");

        reloaded.categorize(category("GROCERIES"), LATER.plusSeconds(1));
        transactions.save(reloaded);

        assertThat(jdbc.queryForMap("select category_source, category_rule_version from transactions"))
                .containsEntry("category_source", "MANUAL").containsEntry("category_rule_version", null);
    }

    @Test
    void transfersHaveNoMerchantInTheDatabase() {
        transactions.add(transfer(anas, aurora, norte, "100.00"));

        assertThat(jdbc.queryForObject("select merchant_key from transactions", String.class)).isNull();
    }

    @Test
    void awaitingAutomationReturnsOnlyUntreatedOrMerchantlessCategorizableTransactionsOfTheWorkspace() {
        Transaction untreated = expense(anas, aurora, "10.00", "Loja Alfa");
        Transaction treated = expense(anas, aurora, "20.00", "Loja Beta");
        treated.clearCategory(LATER);
        Transaction merchantless = expense(anas, aurora, "30.00", "Loja Gama");
        merchantless.categorize(category("SHOPPING"), LATER);
        Transaction other = expense(bobs, persistedAccount(bobs, "Conta do Bob"), "40.00", "Loja Delta");
        addAll(untreated, treated, merchantless, transfer(anas, aurora, norte, "50.00"), other);
        jdbc.update("update transactions set merchant_key = null, merchant_name = null,"
                + " merchant_normalizer_version = null where id = ?", merchantless.id().value());

        List<Transaction> awaiting = transactions.findAwaitingAutomation(anas, null, 100);

        assertThat(awaiting).extracting(t -> t.description().value())
                .containsExactlyInAnyOrder("Loja Alfa", "Loja Gama");
        assertThat(awaiting).extracting(t -> t.id().value()).isSorted();
        // Paginação por id: a partir do primeiro, sobra o segundo.
        assertThat(transactions.findAwaitingAutomation(anas, awaiting.getFirst().id(), 100)).hasSize(1);
        assertThat(transactions.findAwaitingAutomation(anas, null, 1)).hasSize(1);
    }

    @Test
    void automatableByMerchantSkipsManualChoicesOtherNaturesAndOtherWorkspaces() {
        Transaction untreated = expense(anas, aurora, "10.00", "Loja Alfa 01/09");
        Transaction byRule = expense(anas, aurora, "20.00", "Loja Alfa 02/09");
        byRule.categorizeByRule(category("SHOPPING"), CategoryOrigin.LEARNED, LATER);
        Transaction manual = expense(anas, aurora, "30.00", "Loja Alfa 03/09");
        manual.categorize(category("SHOPPING"), LATER);
        Transaction incomeSameMerchant = income(anas, aurora, "40.00", SEP_22, "Loja Alfa");
        Transaction bobsOwn = expense(bobs, persistedAccount(bobs, "Conta do Bob"), "50.00", "Loja Alfa");
        addAll(untreated, byRule, manual, incomeSameMerchant, bobsOwn);

        List<Transaction> similar = transactions.findAutomatableByMerchant(anas, "loja alfa", CategoryNature.EXPENSE);

        assertThat(similar).extracting(t -> t.id()).containsExactlyInAnyOrder(untreated.id(), byRule.id());
        assertThat(transactions.findAutomatableByMerchant(anas, "loja alfa", CategoryNature.INCOME))
                .extracting(t -> t.id()).containsExactly(incomeSameMerchant.id());
        assertThat(transactions.findAutomatableByMerchant(bobs, "loja alfa", CategoryNature.EXPENSE))
                .extracting(t -> t.id()).containsExactly(bobsOwn.id());
    }

    @Test
    void learnedRulesAreUpsertedPerMerchantAndNatureAndIsolatedByWorkspace() {
        LearnedRule alfa = new LearnedRule("loja alfa", category("SHOPPING").asTransactionCategory());
        rules.upsert(anas, alfa, LATER);
        rules.upsert(anas, new LearnedRule("loja alfa", category("GIFTS_DONATIONS").asTransactionCategory()),
                LATER.plusSeconds(1));
        rules.upsert(anas, new LearnedRule("loja alfa", category("OTHER_INCOME").asTransactionCategory()), LATER);
        rules.upsert(bobs, alfa, LATER);

        assertThat(rules.findAll(anas)).extracting(r -> r.category().code().value())
                .containsExactlyInAnyOrder("GIFTS_DONATIONS", "OTHER_INCOME");
        assertThat(rules.findAll(bobs)).extracting(r -> r.category().code().value()).containsExactly("SHOPPING");
        assertThat(rules.findAll(anas).stream().map(LearnedRule::merchantKey).collect(Collectors.toSet()))
                .containsExactly("loja alfa");
    }

    @Test
    void databaseTiesTheRuleToTheCatalogAndTheWorkspace() {
        assertThatThrownBy(() -> rules.upsert(anas,
                new LearnedRule("loja", new com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory(
                        new CategoryCode("GROCERIES"), CategoryNature.INCOME)), LATER))
                .as("natureza da regra não bate com a categoria").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesTheOriginInvariants() {
        Transaction lume = expense(anas, aurora, "86.40", "Loja Alfa");
        transactions.add(lume);

        assertThatThrownBy(() -> jdbc.update("update transactions set category_code = 'DINING_OUT',"
                + " category_nature = 'EXPENSE' where id = ?", lume.id().value()))
                .as("categoria sem origem").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update transactions set category_source = 'RULE_SYSTEM' where id = ?",
                lume.id().value())).as("regra do sistema sem versão").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update transactions set category_rule_version = 'x',"
                + " category_source = 'MANUAL' where id = ?", lume.id().value()))
                .as("versão fora de regra do sistema").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update transactions set merchant_key = null where id = ?",
                lume.id().value())).as("comerciante pela metade").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update transactions set category_source = 'WHATEVER' where id = ?",
                lume.id().value())).as("origem desconhecida").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationMarkedPreviousCategoriesAsManual() {
        // O update da V10 só mexe em linhas com categoria; o invariante vale para todas as que existirem depois dele.
        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where category_code is not null and category_source is null",
                Integer.class)).isZero();
        assertThat(CategorySource.valueOf("MANUAL").allowsAutomation()).isFalse();
    }

    private void addAll(Transaction... batch) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> transactions.addAll(List.of(batch)));
    }

    private Category category(String code) {
        return categories.findByCode(new CategoryCode(code)).orElseThrow();
    }

    private WorkspaceId persistedWorkspace(User owner) {
        Workspace workspace = personalWorkspace(owner);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            users.addIfAbsent(owner);
            workspaces.addPersonalIfAbsent(workspace);
        });
        return workspace.id();
    }

    private AccountId persistedAccount(WorkspaceId workspaceId, String name) {
        Account account = account(workspaceId, name);
        accounts.add(account);
        return account.id();
    }
}
