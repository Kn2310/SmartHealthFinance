package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.usecase.CreateAccount;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.application.usecase.ProvisionCurrentUser;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concorrência de ponta a ponta (caso de uso real + transação + PostgreSQL): retries simultâneos com a mesma
 * Idempotency-Key geram exatamente 1 lançamento, e reembolsos simultâneos nunca ultrapassam o valor original.
 */
class CreateTransactionConcurrencyIT extends IntegrationTest {

    private static final int ATTEMPTS = 8;

    @Autowired
    ProvisionCurrentUser provision;

    @Autowired
    CreateAccount createAccount;

    @Autowired
    CreateTransaction create;

    @Autowired
    JdbcTemplate jdbc;

    private UUID workspaceId;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        authenticate();
        workspaceId = provision.execute().workspaceId();
        AccountView account = createAccount.execute(workspaceId,
                new CreateAccount.Command("Banco Aurora", "CHECKING", null, true));
        accountId = account.id();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateExactlyOneTransaction() throws Exception {
        List<Outcome> outcomes = concurrently(() -> create.execute(workspaceId, expense("same-key", "86.40", null)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes).extracting(o -> o.result().replayed()).containsOnlyOnce(false);
        assertThat(outcomes).extracting(o -> o.result().transaction().id())
                .containsOnly(outcomes.getFirst().result().transaction().id());
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_idempotency_keys")).isEqualTo(1);
    }

    @Test
    void concurrentRefundsNeverExceedTheOriginal() throws Exception {
        UUID purchase = create.execute(workspaceId, expense("purchase", "100.00", null)).transaction().id();

        List<Outcome> outcomes = concurrently(
                () -> create.execute(workspaceId, refund(UUID.randomUUID().toString(), "60.00", purchase)));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .allSatisfy(o -> assertThat(o.failure()).isInstanceOfSatisfying(InvalidValueException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("EXCEEDS_REFUNDABLE")));
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'REFUND'", Integer.class))
                .isEqualTo(1);
    }

    private CreateTransaction.Command expense(String key, String amount, UUID refundOf) {
        return new CreateTransaction.Command(key, refundOf == null ? "EXPENSE" : "REFUND", accountId, null, null,
                amount, "BRL", LocalDate.parse("2026-09-22"), "Bistrô Lume", null, refundOf);
    }

    private CreateTransaction.Command refund(String key, String amount, UUID original) {
        return expense(key, amount, original);
    }

    private List<Outcome> concurrently(Callable<CreateTransaction.Result> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreateTransaction.Result>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS)) {
            for (int i = 0; i < ATTEMPTS; i++) {
                futures.add(pool.submit(() -> {
                    authenticate();
                    try {
                        start.await();
                        return work.call();
                    }
                    finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<CreateTransaction.Result> future : futures) {
                try {
                    outcomes.add(new Outcome(future.get(20, TimeUnit.SECONDS), null));
                }
                catch (ExecutionException ex) {
                    outcomes.add(new Outcome(null, ex.getCause()));
                }
            }
            return outcomes;
        }
    }

    private static void authenticate() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("sub-race")
                .claim("iss", ISSUER)
                .claim("email", "sub-race@example.com")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private record Outcome(CreateTransaction.Result result, Throwable failure) {
        boolean succeeded() {
            return failure == null;
        }
    }
}
