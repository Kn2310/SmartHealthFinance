package com.smarthealthfinance.copilot.application.service;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.copilot.application.dto.Admission;
import com.smarthealthfinance.copilot.domain.enums.AdmissionCode;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Portão do Copilot de ponta a ponta (ADR-0039 §10, §13) com o consentimento (ADR-0038), o Redis e o ledger reais:
 * sem consentimento bloqueia; com ele, o token bucket deixa passar 3 perguntas por minuto.
 */
class CopilotAdmissionIT extends IntegrationTest {

    @Autowired
    CopilotAdmission admission;

    @Autowired
    JdbcTemplate jdbc;

    private UUID anasWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = UUID.fromString(JsonPath.read(mvc.post().uri("/api/v1/users/me").with(request("sub-ana",
                "ana@example.com")).exchange().getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.workspaceId"));
        mvc.post().uri("/api/v1/users/me").with(request("sub-bob", "bob@example.com")).exchange();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksWithoutConsentThenAdmitsUpToTheBucketCapacity() {
        actAs("sub-ana");
        assertThat(admission.admit(anasWorkspace).code()).isEqualTo(AdmissionCode.CONSENT_REQUIRED);

        assertThat(mvc.put().uri("/api/v1/workspaces/{id}/consents/COPILOT_DATA_USE", anasWorkspace)
                .with(request("sub-ana", "ana@example.com")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"granted\": true, \"termsVersion\": \"copilot-data-v1\"}")).hasStatus(HttpStatus.OK);
        actAs("sub-ana"); // a requisição MockMvc limpa o contexto de segurança ao terminar

        for (int i = 0; i < 3; i++) {
            assertThat(admission.admit(anasWorkspace).code()).isEqualTo(AdmissionCode.ADMITTED);
        }
        Admission limited = admission.admit(anasWorkspace);
        assertThat(limited.code()).isEqualTo(AdmissionCode.RATE_LIMITED);
        assertThat(limited.retryAfterValue()).hasValueSatisfying(wait -> assertThat(wait).isPositive());
    }

    @Test
    void anotherPersonCannotBeAdmittedInTheWorkspace() {
        actAs("sub-bob");

        assertThatThrownBy(() -> admission.admit(anasWorkspace)).isInstanceOf(WorkspaceNotFoundException.class);
    }

    private static void actAs(String subject) {
        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(subject).claim("iss", ISSUER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    private static RequestPostProcessor request(String subject, String email) {
        return jwt().jwt(t -> t.subject(subject).claim("iss", ISSUER).claim("email", email).claim("name", "Nome"));
    }
}
