package com.smarthealthfinance.consents.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.repository.ConsentRepository;
import com.smarthealthfinance.consents.domain.valueobject.ConsentId;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** API de consentimentos (ADR-0038 §7) contra Postgres real: estados, cascata, auditoria e eventos. */
class ConsentApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String CONSENTS = "/api/v1/workspaces/{workspaceId}/consents";
    private static final String CONSENT = CONSENTS + "/{purpose}";

    @Autowired
    JdbcTemplate jdbc;

    private String workspace;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
    }

    @Autowired
    ConsentRepository consents;

    @Autowired
    TransactionTemplate transactions;

    /** Duas primeiras concessões simultâneas: a segunda colide na chave única e vira conflito (→ 409 CONFLICT). */
    @Test
    void concurrentFirstGrantIsAConflictNotADuplicate() {
        WorkspaceId workspaceId = new WorkspaceId(UUID.fromString(workspace));
        UserId userId = new UserId(jdbc.queryForObject("select id from users", UUID.class));
        Instant now = Instant.now();

        transactions.executeWithoutResult(status -> consents.add(Consent.grant(ConsentId.generate(now), workspaceId,
                userId, ConsentPurpose.COPILOT_DATA_USE, "copilot-data-v1", now)));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> consents.add(Consent.grant(
                ConsentId.generate(now), workspaceId, userId, ConsentPurpose.COPILOT_DATA_USE, "copilot-data-v1",
                now)))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("select count(*) from consents", Integer.class)).isEqualTo(1);
    }

    @Test
    void listsEveryPurposeAsPendingByDefault() throws Exception {
        MvcTestResult result = mvc.get().uri(CONSENTS, workspace).with(ana()).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat((List<String>) read(result, "$.items[*].purpose"))
                .containsExactly("COPILOT_DATA_USE", "COPILOT_HISTORY");
        assertThat((List<String>) read(result, "$.items[*].state")).containsOnly("PENDING");
        assertThat((List<String>) read(result, "$.items[*].currentTermsVersion"))
                .containsExactly("copilot-data-v1", "copilot-history-v1");
        assertThat((List<Object>) read(result, "$.items[1].requires")).containsExactly("COPILOT_DATA_USE");
        assertThat((Object) read(result, "$.items[0].grantedAt")).isNull();
    }

    @Test
    void grantThenCascadingRevocation() throws Exception {
        assertThat(put("COPILOT_DATA_USE", "{\"granted\": true, \"termsVersion\": \"copilot-data-v1\"}"))
                .hasStatus(HttpStatus.OK);
        MvcTestResult history = put("COPILOT_HISTORY",
                "{\"granted\": true, \"termsVersion\": \"copilot-history-v1\"}");
        assertThat(history).hasStatus(HttpStatus.OK);
        assertThat((List<String>) read(history, "$.items[*].state")).containsOnly("ACTIVE");
        assertThat(read(history, "$.items[0].grantedAt").toString()).endsWith("Z");

        MvcTestResult revoked = put("COPILOT_DATA_USE", "{\"granted\": false}");

        assertThat(revoked).hasStatus(HttpStatus.OK);
        assertThat((List<String>) read(revoked, "$.items[*].state")).containsOnly("REVOKED");
        assertThat(jdbc.queryForList("select action || ':' || cause from consent_audit order by occurred_at, purpose",
                String.class)).containsExactly("GRANTED:USER", "GRANTED:USER", "REVOKED:USER", "REVOKED:CASCADE");
        assertThat(jdbc.queryForList("select event_type from outbox_events where event_type like 'consent.%' "
                + "order by id", String.class))
                .containsExactlyInAnyOrder("consent.granted", "consent.granted", "consent.revoked", "consent.revoked");
    }

    @Test
    void repeatingTheSameGrantDoesNotAuditTwice() {
        String body = "{\"granted\": true, \"termsVersion\": \"copilot-data-v1\"}";
        assertThat(put("COPILOT_DATA_USE", body)).hasStatus(HttpStatus.OK);
        assertThat(put("COPILOT_DATA_USE", body)).hasStatus(HttpStatus.OK);

        assertThat(jdbc.queryForObject("select count(*) from consent_audit", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from consents", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsOutdatedTermsMissingPrerequisiteUnknownPurposeAndMissingFlag() {
        assertError(put("COPILOT_DATA_USE", "{\"granted\": true, \"termsVersion\": \"copilot-data-v0\"}"),
                HttpStatus.CONFLICT, "CONSENT_TERMS_OUTDATED");
        assertError(put("COPILOT_DATA_USE", "{\"granted\": true}"), HttpStatus.CONFLICT, "CONSENT_TERMS_OUTDATED");
        assertError(put("COPILOT_HISTORY", "{\"granted\": true, \"termsVersion\": \"copilot-history-v1\"}"),
                HttpStatus.CONFLICT, "CONSENT_PREREQUISITE_MISSING");
        assertError(put("INSIGHTS_GENERATION", "{\"granted\": true, \"termsVersion\": \"x\"}"),
                HttpStatus.NOT_FOUND, "CONSENT_PURPOSE_NOT_FOUND");
        assertError(put("COPILOT_DATA_USE", "{\"termsVersion\": \"copilot-data-v1\"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("select count(*) from consents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consent_audit", Integer.class)).isZero();
    }

    @Test
    void auditTrailIsAppendOnly() {
        assertThat(put("COPILOT_DATA_USE", "{\"granted\": true, \"termsVersion\": \"copilot-data-v1\"}"))
                .hasStatus(HttpStatus.OK);

        assertThatThrownBy(() -> jdbc.update("update consent_audit set cause = 'CASCADE'"))
                .hasMessageContaining("append-only");
    }

    @Test
    void anotherPersonCannotSeeOrChangeTheConsents() {
        assertThat(put("COPILOT_DATA_USE", "{\"granted\": true, \"termsVersion\": \"copilot-data-v1\"}"))
                .hasStatus(HttpStatus.OK);
        mvc.post().uri(ME).with(bob()).exchange();

        assertError(mvc.get().uri(CONSENTS, workspace).with(bob()).exchange(), HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND");
        assertError(mvc.put().uri(CONSENT, workspace, "COPILOT_DATA_USE").with(bob())
                .contentType(MediaType.APPLICATION_JSON).content("{\"granted\": false}").exchange(),
                HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND");
        assertThat(jdbc.queryForObject("select status from consents", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void eventPayloadCarriesOnlyIdsAndCodes() {
        assertThat(put("COPILOT_DATA_USE", "{\"granted\": true, \"termsVersion\": \"copilot-data-v1\"}"))
                .hasStatus(HttpStatus.OK);

        Map<String, Object> row = jdbc.queryForMap(
                "select payload::text as payload from outbox_events where event_type = 'consent.granted'");
        assertThat(row.get("payload").toString()).doesNotContain("ana@example.com")
                .contains("\"purpose\": \"COPILOT_DATA_USE\"");
    }

    private MvcTestResult put(String purpose, String body) {
        return mvc.put().uri(CONSENT, workspace, purpose).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo(code));
    }

    private static RequestPostProcessor ana() {
        return identity("sub-ana", "ana@example.com");
    }

    private static RequestPostProcessor bob() {
        return identity("sub-bob", "bob@example.com");
    }

    private static RequestPostProcessor identity(String subject, String email) {
        return jwt().jwt(token -> token.subject(subject).claim("iss", ISSUER).claim("email", email).claim("name", "Nome"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(MvcTestResult result, String path) throws Exception {
        return (T) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
