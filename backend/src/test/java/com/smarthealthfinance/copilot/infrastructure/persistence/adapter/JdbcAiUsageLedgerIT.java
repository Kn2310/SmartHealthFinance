package com.smarthealthfinance.copilot.infrastructure.persistence.adapter;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Usage ledger (ADR-0039 §10) contra Postgres real: gravação exata, janelas por Workspace e constraints. */
class JdbcAiUsageLedgerIT extends IntegrationTest {

    private static final Instant T = Instant.parse("2026-11-10T12:00:00Z");

    @Autowired
    JdbcAiUsageLedger ledger;

    @Autowired
    JdbcTemplate jdbc;

    private WorkspaceId anasWorkspace;
    private UserId ana;
    private WorkspaceId bobsWorkspace;
    private UserId bob;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = provision("sub-ana", "ana@example.com");
        ana = new UserId(jdbc.queryForObject("select id from users where email = 'ana@example.com'", UUID.class));
        bobsWorkspace = provision("sub-bob", "bob@example.com");
        bob = new UserId(jdbc.queryForObject("select id from users where email = 'bob@example.com'", UUID.class));
    }

    private WorkspaceId provision(String subject, String email) throws Exception {
        String body = mvc.post().uri("/api/v1/users/me")
                .with(jwt().jwt(t -> t.subject(subject).claim("iss", ISSUER).claim("email", email).claim("name", "N")))
                .exchange().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new WorkspaceId(UUID.fromString(JsonPath.read(body, "$.workspaceId")));
    }

    private AiUsage usage(WorkspaceId workspace, UserId user, AiRequestType type, TokenUsage tokens, Instant at) {
        return new AiUsage(UUID.randomUUID(), workspace, user, UUID.randomUUID(), type, "simulated", "simulated-v1",
                tokens, new BigDecimal("0.018200"), "pricing-2026-11", 1_234, AiOutcome.OK, at);
    }

    @Test
    void storesEveryFieldExactly() {
        AiUsage usage = usage(anasWorkspace, ana, AiRequestType.ANSWER, new TokenUsage(6_000, 600, 2_000, 10), T);
        ledger.append(usage);

        var row = jdbc.queryForMap("select * from ai_usage where id = ?", usage.id());
        assertThat(row.get("workspace_id")).isEqualTo(anasWorkspace.value());
        assertThat(row.get("user_id")).isEqualTo(ana.value());
        assertThat(row.get("request_id")).isEqualTo(usage.requestId());
        assertThat(row.get("request_type")).isEqualTo("ANSWER");
        assertThat(row.get("input_tokens")).isEqualTo(6_000L);
        assertThat(row.get("output_tokens")).isEqualTo(600L);
        assertThat(row.get("cache_read_tokens")).isEqualTo(2_000L);
        assertThat(row.get("cache_write_tokens")).isEqualTo(10L);
        assertThat((BigDecimal) row.get("cost")).isEqualByComparingTo("0.018200");
        assertThat(((BigDecimal) row.get("cost")).scale()).isEqualTo(6);
        assertThat(row.get("pricing_version")).isEqualTo("pricing-2026-11");
        assertThat(row.get("latency_ms")).isEqualTo(1_234L);
        assertThat(row.get("outcome")).isEqualTo("OK");
        assertThat(row.keySet()).doesNotContain("prompt", "question", "answer", "content");
    }

    @Test
    void countsQuestionsAndSumsInputTokensPerWorkspaceInHalfOpenWindows() {
        ledger.append(usage(anasWorkspace, ana, AiRequestType.INTENT, new TokenUsage(100, 5, 50, 0), T));
        ledger.append(usage(anasWorkspace, ana, AiRequestType.ANSWER, new TokenUsage(1_000, 80, 200, 20), T));
        ledger.append(usage(anasWorkspace, ana, AiRequestType.INTENT, new TokenUsage(100, 5, 0, 0),
                T.plusSeconds(3_600)));
        ledger.append(usage(bobsWorkspace, bob, AiRequestType.INTENT, new TokenUsage(9_999, 0, 0, 0), T));

        assertThat(ledger.countQuestions(anasWorkspace, T, T.plusSeconds(3_600))).isEqualTo(1);
        assertThat(ledger.countQuestions(anasWorkspace, T, T.plusSeconds(3_601))).isEqualTo(2);
        assertThat(ledger.sumInputTokens(anasWorkspace, T, T.plusSeconds(3_600))).isEqualTo(100 + 50 + 1_000 + 200 + 20);
        assertThat(ledger.countQuestions(bobsWorkspace, T, T.plusSeconds(1))).isEqualTo(1);
        assertThat(ledger.sumInputTokens(anasWorkspace, T.minusSeconds(10), T)).isZero();
    }

    @Test
    void providerFailuresDoNotCountAsQuestionsButRefusalsDo() {
        for (AiOutcome outcome : AiOutcome.values()) {
            ledger.append(new AiUsage(UUID.randomUUID(), anasWorkspace, ana, UUID.randomUUID(), AiRequestType.INTENT,
                    "simulated", "simulated-v1", TokenUsage.NONE, BigDecimal.ZERO, "pricing-2026-11", 1, outcome, T));
        }

        // OK, REFUSED e INVALID_OUTPUT contam; UNAVAILABLE, TIMEOUT e RATE_LIMITED não.
        assertThat(ledger.countQuestions(anasWorkspace, T, T.plusSeconds(1))).isEqualTo(3);
    }

    @Test
    void constraintsMirrorTheDomain() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into ai_usage (id, workspace_id, user_id, request_id, request_type, provider, model,
                  input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost, pricing_version,
                  latency_ms, outcome, created_at)
                values (?, ?, ?, ?, 'CHAT', 'x', 'm', 0, 0, 0, 0, 0, 'v', 0, 'OK', now())
                """, UUID.randomUUID(), anasWorkspace.value(), ana.value(), UUID.randomUUID()))
                .hasMessageContaining("ck_ai_usage_request_type");
        assertThatThrownBy(() -> jdbc.update("""
                insert into ai_usage (id, workspace_id, user_id, request_id, request_type, provider, model,
                  input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost, pricing_version,
                  latency_ms, outcome, created_at)
                values (?, ?, ?, ?, 'INTENT', 'x', 'm', -1, 0, 0, 0, 0, 'v', 0, 'OK', now())
                """, UUID.randomUUID(), anasWorkspace.value(), ana.value(), UUID.randomUUID()))
                .hasMessageContaining("ck_ai_usage_tokens");
    }
}
