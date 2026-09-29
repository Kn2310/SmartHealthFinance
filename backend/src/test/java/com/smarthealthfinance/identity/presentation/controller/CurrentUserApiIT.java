package com.smarthealthfinance.identity.presentation.controller;

import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.nio.charset.StandardCharsets;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ExtendWith(OutputCaptureExtension.class)
class CurrentUserApiIT extends IntegrationTest {

	private static final String ME = "/api/v1/users/me";

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanUsers() {
		IdentityTables.clean(jdbc);
	}

	// --- autenticação ---

	@Test
	void allOperationsRequireAuthentication() {
		assertThat(mvc.post().uri(ME)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.patch().uri(ME).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ana\"}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void tokenWithoutIssuerIsUnauthenticated() {
		assertThat(mvc.post().uri(ME).with(jwt().jwt(t -> t.subject("sub-ana").claim("email", "ana@example.com"))))
			.hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("UNAUTHENTICATED"));
	}

	// --- provisionamento ---

	@Test
	void getBeforeProvisioningReturnsUserNotProvisioned() {
		assertThat(mvc.get().uri(ME).with(ana())).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("USER_NOT_PROVISIONED"))
			.hasPathSatisfying("$.traceId", traceId -> assertThat(traceId).asString().isNotBlank());
	}

	@Test
	void firstProvisioningCreatesUserFromTokenClaims() {
		assertThat(mvc.post().uri(ME).with(identity(ISSUER, "sub-ana", "Ana.Silva@Example.com", "Ana Silva")))
			.hasStatus(HttpStatus.CREATED)
			.hasHeader(HttpHeaders.LOCATION, ME)
			.bodyJson()
			.hasPathSatisfying("$.id", id -> assertThat(id).asString().isNotBlank())
			.hasPathSatisfying("$.email", email -> assertThat(email).asString().isEqualTo("ana.silva@example.com"))
			.hasPathSatisfying("$.displayName", name -> assertThat(name).asString().isEqualTo("Ana Silva"))
			.hasPathSatisfying("$.status", status -> assertThat(status).asString().isEqualTo("ACTIVE"))
			.hasPathSatisfying("$.createdAt", createdAt -> assertThat(createdAt).asString().endsWith("Z"));
	}

	@Test
	void provisioningIsIdempotent() throws Exception {
		String firstId = idFrom(mvc.post().uri(ME).with(ana()).exchange());

		MvcTestResult second = mvc.post().uri(ME).with(ana()).exchange();

		assertThat(second).hasStatus(HttpStatus.OK);
		assertThat(idFrom(second)).isEqualTo(firstId);
		assertThat(countUsers()).isEqualTo(1);
	}

	@Test
	void createdAtIsStableBetweenCreationAndLaterReads() throws Exception {
		String createdOnPost = createdAtFrom(mvc.post().uri(ME).with(ana()).exchange());

		assertThat(createdAtFrom(mvc.get().uri(ME).with(ana()).exchange())).isEqualTo(createdOnPost);
	}

	@Test
	void provisioningSyncsEmailButKeepsLocalDisplayName() {
		provision(identity(ISSUER, "sub-ana", "ana@example.com", "Ana Silva"));
		assertThat(patchDisplayName(ana(), "Ana Souza")).hasStatus(HttpStatus.OK);

		assertThat(mvc.post().uri(ME).with(identity(ISSUER, "sub-ana", "ana.nova@example.com", "Nome do IdP")))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.hasPathSatisfying("$.email", email -> assertThat(email).asString().isEqualTo("ana.nova@example.com"))
			.hasPathSatisfying("$.displayName", name -> assertThat(name).asString().isEqualTo("Ana Souza"));
	}

	@Test
	void provisioningWithoutEmailClaimIsRejected() {
		assertThat(mvc.post().uri(ME).with(jwt().jwt(t -> t.subject("sub-ana").claim("iss", ISSUER))))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.hasPathSatisfying("$.code",
					code -> assertThat(code).asString().isEqualTo("IDENTITY_CLAIMS_INCOMPLETE"));
		assertThat(countUsers()).isZero();
	}

	@Test
	void provisioningWithInvalidEmailClaimIsRejected() {
		assertThat(mvc.post().uri(ME).with(identity(ISSUER, "sub-ana", "not-an-email", "Ana")))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(countUsers()).isZero();
	}

	@Test
	void provisioningNeverLogsEmail(CapturedOutput output) {
		provision(identity(ISSUER, "sub-ana", "ana.privada@example.com", "Ana"));

		assertThat(output).contains("User provisioned").doesNotContain("ana.privada@example.com");
	}

	// --- perfil ---

	@Test
	void updatesDisplayName() {
		provision(ana());

		assertThat(patchDisplayName(ana(), "  Ana Souza  ")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.hasPathSatisfying("$.displayName", name -> assertThat(name).asString().isEqualTo("Ana Souza"));
		assertThat(mvc.get().uri(ME).with(ana())).hasStatus(HttpStatus.OK)
			.bodyJson()
			.hasPathSatisfying("$.displayName", name -> assertThat(name).asString().isEqualTo("Ana Souza"));
	}

	@Test
	void rejectsBlankDisplayName() {
		provision(ana());

		assertThat(patchDisplayName(ana(), " ")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("VALIDATION_FAILED"))
			.hasPathSatisfying("$.details[0].field",
					field -> assertThat(field).asString().isEqualTo("displayName"));
	}

	@Test
	void rejectsTooLongDisplayName() {
		provision(ana());

		assertThat(patchDisplayName(ana(), "a".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.hasPathSatisfying("$.details[0].field",
					field -> assertThat(field).asString().isEqualTo("displayName"));
	}

	@Test
	void rejectsControlCharactersThroughDomainValidation() {
		provision(ana());

		assertThat(mvc.patch().uri(ME).with(ana()).contentType(MediaType.APPLICATION_JSON).content("""
				{"displayName": "Ana\\tSilva"}
				"""))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("VALIDATION_FAILED"))
			.hasPathSatisfying("$.details[0].field",
					field -> assertThat(field).asString().isEqualTo("displayName"))
			.hasPathSatisfying("$.details[0].code",
					reason -> assertThat(reason).asString().isEqualTo("INVALID_CHARACTERS"));
	}

	@Test
	void patchBeforeProvisioningReturnsUserNotProvisioned() {
		assertThat(patchDisplayName(ana(), "Ana")).hasStatus(HttpStatus.NOT_FOUND);
	}

	// --- usuário desativado ---

	@Test
	void disabledUserIsForbiddenEverywhere() {
		provision(ana());
		jdbc.update("update users set status = 'DISABLED' where oidc_subject = ?", "sub-ana");

		assertThat(mvc.get().uri(ME).with(ana())).hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("USER_DISABLED"));
		assertThat(mvc.post().uri(ME).with(ana())).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(patchDisplayName(ana(), "Outro")).hasStatus(HttpStatus.FORBIDDEN);
	}

	// --- isolamento entre identidades ---

	@Test
	void identitiesAreIsolated() throws Exception {
		RequestPostProcessor bob = identity(ISSUER, "sub-bob", "bob@example.com", "Bob");
		String anaId = idFrom(mvc.post().uri(ME).with(ana()).exchange());
		String bobId = idFrom(mvc.post().uri(ME).with(bob).exchange());
		String otherIssuerId = idFrom(mvc.post()
			.uri(ME)
			.with(identity("https://other.idp/realms/x", "sub-ana", "ana@example.com", "Ana"))
			.exchange());

		assertThat(anaId).isNotEqualTo(bobId).isNotEqualTo(otherIssuerId);

		assertThat(patchDisplayName(bob, "Bob Alterado")).hasStatus(HttpStatus.OK);

		assertThat(mvc.get().uri(ME).with(ana())).bodyJson()
			.hasPathSatisfying("$.displayName", name -> assertThat(name).asString().isEqualTo("Ana Silva"));
	}

	// --- helpers ---

	private static RequestPostProcessor ana() {
		return identity(ISSUER, "sub-ana", "ana@example.com", "Ana Silva");
	}

	private static RequestPostProcessor identity(String issuer, String subject, String email, String name) {
		return jwt().jwt(token -> token.subject(subject).claim("iss", issuer).claim("email", email).claim("name", name));
	}

	private void provision(RequestPostProcessor identity) {
		assertThat(mvc.post().uri(ME).with(identity)).hasStatus2xxSuccessful();
	}

	private MvcTestResult patchDisplayName(RequestPostProcessor identity, String displayName) {
		return mvc.patch()
			.uri(ME)
			.with(identity)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\": \"" + displayName + "\"}")
			.exchange();
	}

	private static String idFrom(MvcTestResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
	}

	private static String createdAtFrom(MvcTestResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.createdAt");
	}

	private int countUsers() {
		return jdbc.queryForObject("select count(*) from users", Integer.class);
	}

}
