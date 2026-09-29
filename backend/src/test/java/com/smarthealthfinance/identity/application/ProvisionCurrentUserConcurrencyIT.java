package com.smarthealthfinance.identity.application;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Provisionamento concorrente de ponta a ponta (caso de uso real + transação + PostgreSQL):
 * N requisições simultâneas da mesma identidade resultam em exatamente 1 usuário, 1 Workspace e 1 membership.
 */
class ProvisionCurrentUserConcurrencyIT extends IntegrationTest {

	private static final int ATTEMPTS = 8;

	@Autowired
	ProvisionCurrentUser provision;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		IdentityTables.clean(jdbc);
	}

	@Test
	void concurrentFirstProvisioningCreatesExactlyOneUserAndWorkspace() throws Exception {
		List<ProvisionCurrentUser.Result> results = provisionConcurrently("sub-race");

		assertThat(results).extracting(ProvisionCurrentUser.Result::created).containsOnlyOnce(true);
		assertThat(results).extracting(result -> result.user().id()).containsOnly(results.getFirst().user().id());
		assertThat(results).extracting(ProvisionCurrentUser.Result::workspaceId)
			.containsOnly(results.getFirst().workspaceId());
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("workspaces")).isEqualTo(1);
		assertThat(count("workspace_memberships")).isEqualTo(1);
	}

	@Test
	void concurrentProvisioningOfLegacyUserCreatesExactlyOneWorkspace() throws Exception {
		jdbc.update("""
				insert into users (id, oidc_issuer, oidc_subject, email, display_name, status, created_at, updated_at)
				values (?, ?, 'sub-legacy', 'sub-legacy@example.com', 'Legado', 'ACTIVE', now(), now())
				""", UUID.randomUUID(), ISSUER);

		List<ProvisionCurrentUser.Result> results = provisionConcurrently("sub-legacy");

		assertThat(results).extracting(ProvisionCurrentUser.Result::created).containsOnly(false);
		assertThat(results).extracting(ProvisionCurrentUser.Result::workspaceId)
			.containsOnly(results.getFirst().workspaceId());
		assertThat(count("workspaces")).isEqualTo(1);
		assertThat(count("workspace_memberships")).isEqualTo(1);
	}

	private List<ProvisionCurrentUser.Result> provisionConcurrently(String subject) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<ProvisionCurrentUser.Result>> futures = new ArrayList<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS)) {
			for (int i = 0; i < ATTEMPTS; i++) {
				futures.add(pool.submit(() -> {
					SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(subject)));
					try {
						start.await();
						return provision.execute();
					}
					finally {
						SecurityContextHolder.clearContext();
					}
				}));
			}
			start.countDown();

			List<ProvisionCurrentUser.Result> results = new ArrayList<>();
			for (Future<ProvisionCurrentUser.Result> future : futures) {
				results.add(future.get(20, TimeUnit.SECONDS));
			}
			return results;
		}
	}

	private static Jwt jwt(String subject) {
		return Jwt.withTokenValue("test-token")
			.header("alg", "none")
			.subject(subject)
			.claim("iss", ISSUER)
			.claim("email", subject + "@example.com")
			.build();
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

}
