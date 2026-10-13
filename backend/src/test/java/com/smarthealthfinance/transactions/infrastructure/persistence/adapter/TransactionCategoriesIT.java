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
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.openingBalance;
import static com.smarthealthfinance.transactions.TransactionsFixtures.refund;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Catálogo da V9, persistência da categoria e as garantias do banco (ADR-0012 §3/§10). */
class TransactionCategoriesIT extends IntegrationTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);

    /** Exatamente a tabela do ADR-0012 §3, na ordem de exibição. */
    private static final List<String> EXPENSE_CODES = List.of("HOUSING", "HOME", "GROCERIES", "DINING_OUT",
            "TRANSPORT", "HEALTH", "FITNESS", "PERSONAL_CARE", "EDUCATION", "CHILDREN", "PETS", "LEISURE",
            "SUBSCRIPTIONS", "SHOPPING", "CLOTHING", "TRAVEL", "INSURANCE", "GIFTS_DONATIONS", "BANK_FEES", "SERVICES",
            "TAXES", "OTHER_EXPENSE");
    private static final List<String> INCOME_CODES = List.of("SALARY", "SELF_EMPLOYED", "INVESTMENT_INCOME",
            "RENTAL_INCOME", "BENEFITS", "OTHER_INCOME");

    @Autowired
    CategoryRepository categories;

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
    private AccountId aurora;
    private AccountId norte;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        anas = persistedWorkspace(activeUser("sub-ana"));
        aurora = persistedAccount(anas, "Banco Aurora");
        norte = persistedAccount(anas, "Banco Norte");
    }

    // --- catálogo ---

    @Test
    void catalogMatchesTheAdr() {
        List<Category> all = categories.findAll();

        assertThat(all).filteredOn(category -> category.nature() == CategoryNature.EXPENSE)
                .extracting(category -> category.code().value()).containsExactlyElementsOf(EXPENSE_CODES);
        assertThat(all).filteredOn(category -> category.nature() == CategoryNature.INCOME)
                .extracting(category -> category.code().value()).containsExactlyElementsOf(INCOME_CODES);
        assertThat(all).hasSize(28).allMatch(Category::active);
        assertThat(all.getFirst().code().value()).isEqualTo("HOUSING");
        assertThat(all.getFirst().name()).isEqualTo("Moradia");
        assertThat(categories.findByCode(new CategoryCode("DINING_OUT")).orElseThrow().name())
                .isEqualTo("Alimentação fora");
        assertThat(categories.findByCode(new CategoryCode("UNKNOWN"))).isEmpty();
    }

    // --- persistência ---

    @Test
    void categoryRoundTripsAndCanBeCleared() {
        Transaction lume = expense(anas, aurora, "86.40", "Bistrô Lume");
        transactions.add(lume);

        Transaction loaded = transactions.findById(anas, lume.id()).orElseThrow();
        loaded.categorize(category("DINING_OUT"), LATER);
        transactions.save(loaded);

        Transaction categorized = transactions.findById(anas, lume.id()).orElseThrow();
        assertThat(categorized.category()).contains(category("DINING_OUT").asTransactionCategory());
        assertThat(categorized.amount()).isEqualTo(lume.amount());
        assertThat(categorized.version()).isEqualTo(1);

        categorized.clearCategory(LATER);
        transactions.save(categorized);
        assertThat(transactions.findById(anas, lume.id()).orElseThrow().category()).isEmpty();
    }

    @Test
    void filtersByCategoryAndUncategorized() {
        Transaction lume = expense(anas, aurora, "86.40", "Bistrô Lume");
        Transaction market = expense(anas, aurora, "296.08", "Mercado Bom Preço");
        Transaction salary = income(anas, aurora, "6500", SEP_22, "Salário");
        List.of(lume, market, salary).forEach(transactions::add);
        categorizeAndSave(lume, "DINING_OUT");
        categorizeAndSave(salary, "SALARY");

        assertThat(descriptions(new TransactionCriteria(null, null, null, null, null, null,
                new CategoryCode("DINING_OUT"), false))).containsExactly("Bistrô Lume");
        assertThat(descriptions(new TransactionCriteria(null, null, null, null, null, null, null, true)))
                .containsExactly("Mercado Bom Preço");
    }

    // --- garantias do banco, independentes da aplicação ---

    @Test
    void databaseRejectsCategoryOnTransferAndAdjustment() {
        Transaction move = transfer(anas, aurora, norte, "500");
        Transaction opening = openingBalance(anas, aurora, AdjustmentDirection.INCREASE, "1000");
        List.of(move, opening).forEach(transactions::add);

        assertThatThrownBy(() -> setCategory(move.id().value(), "DINING_OUT", "EXPENSE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setCategory(opening.id().value(), "SALARY", "INCOME"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseTiesNatureToType() {
        Transaction lume = expense(anas, aurora, "86.40", "Bistrô Lume");
        Transaction salary = income(anas, aurora, "6500", SEP_22, "Salário");
        Transaction devolution = refund(anas, aurora, "10", null);
        List.of(lume, salary, devolution).forEach(transactions::add);

        assertThatThrownBy(() -> setCategory(lume.id().value(), "SALARY", "INCOME"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setCategory(salary.id().value(), "DINING_OUT", "EXPENSE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setCategory(devolution.id().value(), "SALARY", "INCOME"))
                .isInstanceOf(DataIntegrityViolationException.class);
        setCategory(devolution.id().value(), "DINING_OUT", "EXPENSE");
    }

    /** A natureza gravada na transação precisa ser a do catálogo (FK composta). */
    @Test
    void databaseRejectsForgedNatureAndHalfPairs() {
        Transaction salary = income(anas, aurora, "6500", SEP_22, "Salário");
        transactions.add(salary);

        assertThatThrownBy(() -> setCategory(salary.id().value(), "DINING_OUT", "INCOME"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setCategory(salary.id().value(), "SALARY", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setCategory(salary.id().value(), null, "INCOME"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setCategory(salary.id().value(), "UNKNOWN", "INCOME"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void setCategory(UUID transactionId, String code, String nature) {
        jdbc.update("update transactions set category_code = ?, category_nature = ? where id = ?", code, nature,
                transactionId);
    }

    private void categorizeAndSave(Transaction transaction, String code) {
        Transaction loaded = transactions.findById(anas, transaction.id()).orElseThrow();
        loaded.categorize(category(code), LATER);
        transactions.save(loaded);
    }

    private Category category(String code) {
        return categories.findByCode(new CategoryCode(code)).orElseThrow();
    }

    private List<String> descriptions(TransactionCriteria criteria) {
        return transactions.search(anas, criteria, 0, 100).items().stream().map(t -> t.description().value())
                .toList();
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
