package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.application.ReferenceTransactionSummary;
import com.smarthealthfinance.transactions.application.port.TransactionSummaryReader;
import com.smarthealthfinance.transactions.application.port.TransactionSummaryReader.TransactionSummary;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.create;
import static org.assertj.core.api.Assertions.assertThat;

/** Paridade do resumo do período (ADR-0016): SQL × referência do domínio, para várias combinações de filtros. */
class JdbcTransactionSummaryReaderIT extends IntegrationTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @Autowired
    TransactionSummaryReader reader;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CategoryRepository categories;

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
    private AccountId reserva;
    private AccountId antiga;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        anas = persistedWorkspace(activeUser("sub-ana"));
        WorkspaceId bobs = persistedWorkspace(activeUser("sub-bob"));
        aurora = persisted(account(anas, "Banco Aurora"));
        reserva = persisted(Account.create(AccountId.generate(CREATED_AT), anas, new AccountName("Reserva"),
                AccountType.SAVINGS, new InstitutionName("Banco Norte"), BRL, false, CREATED_AT));
        antiga = persisted(account(anas, "Conta antiga"));
        AccountId bobsAccount = persisted(account(bobs, "Conta do Bob"));

        Transaction lume = tx(anas, TransactionType.EXPENSE, aurora, null, null, "86.40", "2026-09-22", "Bistrô Lume",
                TransactionStatus.POSTED);
        lume.categorize(categories.findByCode(new CategoryCode("DINING_OUT")).orElseThrow(), CREATED_AT);
        Transaction reversed = tx(anas, TransactionType.EXPENSE, aurora, null, null, "77.00", "2026-09-08", "Loja",
                TransactionStatus.POSTED);
        reversed.reverse(CREATED_AT);
        inTransaction(() -> transactions.addAll(List.of(
                tx(anas, TransactionType.INCOME, aurora, null, null, "6500.00", "2026-09-05", "Salário",
                        TransactionStatus.POSTED),
                lume,
                tx(anas, TransactionType.EXPENSE, aurora, null, null, "50.00", "2026-09-10", "Mercado 100%_sol",
                        TransactionStatus.POSTED),
                tx(anas, TransactionType.EXPENSE, reserva, null, null, "12.30", "2026-09-12", "Café na reserva",
                        TransactionStatus.POSTED),
                tx(anas, TransactionType.EXPENSE, antiga, null, null, "220.50", "2026-09-14", "Posto",
                        TransactionStatus.POSTED),
                tx(anas, TransactionType.EXPENSE, aurora, null, null, "999.00", "2026-09-20", "Pendente",
                        TransactionStatus.PENDING),
                reversed,
                create(anas, TransactionType.REFUND, aurora, null, null, "30.00", LocalDate.parse("2026-09-23"),
                        "Estorno Bistrô", TransactionStatus.POSTED, lume.id(), CREATED_AT),
                tx(anas, TransactionType.TRANSFER, aurora, reserva, null, "500.00", "2026-09-05", "Para reserva",
                        TransactionStatus.POSTED),
                tx(anas, TransactionType.ADJUSTMENT, aurora, null, AdjustmentDirection.INCREASE, "1000.00",
                        "2026-09-01", "Saldo inicial", TransactionStatus.POSTED),
                tx(anas, TransactionType.INCOME, aurora, null, null, "300.00", "2026-08-18", "Freelance",
                        TransactionStatus.POSTED),
                tx(bobs, TransactionType.EXPENSE, bobsAccount, null, null, "10.00", "2026-09-22", "Do Bob",
                        TransactionStatus.POSTED))));
        jdbc.update("update accounts set status = 'ARCHIVED' where id = ?", antiga.value());
    }

    @Test
    void matchesTheDomainReferenceForEveryFilterCombination() {
        LocalDate sep1 = LocalDate.parse("2026-09-01");
        LocalDate sep30 = LocalDate.parse("2026-09-30");
        List<TransactionCriteria> cases = List.of(
                TransactionCriteria.none(),
                new TransactionCriteria(sep1, sep30, null, null, null, null),
                new TransactionCriteria(sep1, sep30, TransactionType.EXPENSE, null, null, null),
                new TransactionCriteria(null, null, null, null, aurora, null),
                new TransactionCriteria(null, null, null, null, reserva, null),
                new TransactionCriteria(null, null, null, null, antiga, null),
                new TransactionCriteria(null, null, null, null, null, "bistrô"),
                new TransactionCriteria(null, null, null, null, null, "100%_"),
                new TransactionCriteria(null, null, null, null, null, null, new CategoryCode("DINING_OUT"), false),
                new TransactionCriteria(null, null, null, null, null, null, null, true),
                new TransactionCriteria(sep1, sep30, null, TransactionStatus.PENDING, null, null));
        ReferenceTransactionSummary reference = new ReferenceTransactionSummary(transactions, accounts);

        for (TransactionCriteria criteria : cases) {
            assertThat(reader.summarize(anas, criteria, BRL)).as(criteria.toString())
                    .isEqualTo(reference.summarize(anas, criteria, BRL));
        }
    }

    @Test
    void septemberWithoutFiltersFollowsTheOverviewRules() {
        TransactionSummary september = reader.summarize(anas,
                new TransactionCriteria(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), null, null, null,
                        null), BRL);

        // Despesas só de contas ativas incluídas no total: 86,40 + 50,00 (reserva fora do total, antiga arquivada).
        assertThat(september.expense().amount()).isEqualByComparingTo("136.40");
        assertThat(september.income().amount()).isEqualByComparingTo("6500.00");
        assertThat(september.refunds().amount()).isEqualByComparingTo("30.00");
        assertThat(september.net().amount()).isEqualByComparingTo("6393.60");
        assertThat(september.transferCount()).isEqualTo(1);
        assertThat(september.transferVolume().amount()).isEqualByComparingTo("500.00");
        assertThat(september.pendingCount()).isEqualTo(1);
        // Lançadas de todas as contas (o ajuste também é um lançamento da lista).
        assertThat(september.postedCount()).isEqualTo(8);
    }

    private Transaction tx(WorkspaceId workspace, TransactionType type, AccountId account, AccountId destination,
                           AdjustmentDirection direction, String amount, String date, String description,
                           TransactionStatus status) {
        return create(workspace, type, account, destination, direction, amount, LocalDate.parse(date), description,
                status, null, CREATED_AT);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private WorkspaceId persistedWorkspace(User owner) {
        Workspace workspace = personalWorkspace(owner);
        inTransaction(() -> {
            users.addIfAbsent(owner);
            workspaces.addPersonalIfAbsent(workspace);
        });
        return workspace.id();
    }

    private AccountId persisted(Account account) {
        accounts.add(account);
        return account.id();
    }
}
