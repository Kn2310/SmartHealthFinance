package com.smarthealthfinance.identity.presentation.controller;

import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ExtendWith(OutputCaptureExtension.class)
class WorkspaceApiIT extends IntegrationTest {

	private static final String ME = "/api/v1/users/me";

	private static final String WORKSPACES = "/api/v1/workspaces";

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		IdentityTables.clean(jdbc);
	}

	// --- provisionamento ---

	@Test
	void provisioningReturnsThePersonalWorkspaceId() throws Exception {
		MvcTestResult result = mvc.post().uri(ME).with(ana()).exchange();

		assertThat(result).hasStatus(HttpStatus.CREATED);
		String workspaceId = workspaceIdFrom(result);
		assertThat(UUID.fromString(workspaceId).version()).isEqualTo(7);
		assertThat(count("workspaces")).isEqualTo(1);
		assertThat(count("workspace_memberships")).isEqualTo(1);
	}

	@Test
	void repeatedProvisioningReturnsTheSameWorkspace() throws Exception {
		String first = workspaceIdFrom(mvc.post().uri(ME).with(ana()).exchange());

		MvcTestResult second = mvc.post().uri(ME).with(ana()).exchange();

		assertThat(second).hasStatus(HttpStatus.OK);
		assertThat(workspaceIdFrom(second)).isEqualTo(first);
		assertThat(count("workspaces")).isEqualTo(1);
	}

	@Test
	void userProvisionedBeforeWorkspacesGetsOneOnNextProvisioning() throws Exception {
		jdbc.update("""
				insert into users (id, oidc_issuer, oidc_subject, email, display_name, status, created_at, updated_at)
				values (?, ?, 'sub-ana', 'ana@example.com', 'Ana Silva', 'ACTIVE', now(), now())
				""", UUID.randomUUID(), ISSUER);
		assertThat(count("workspaces")).isZero();

		MvcTestResult result = mvc.post().uri(ME).with(ana()).exchange();

		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(workspaceIdFrom(result)).isNotBlank();
		assertThat(count("workspaces")).isEqualTo(1);
	}

	@Test
	void provisioningNeverLogsEmail(CapturedOutput output) {
		assertThat(mvc.post().uri(ME).with(identity("sub-ana", "ana.privada@example.com")))
			.hasStatus(HttpStatus.CREATED);

		assertThat(output).contains("Personal workspace created").doesNotContain("ana.privada@example.com");
	}

	// --- consulta ---

	@Test
	void listsThePersonalWorkspace() throws Exception {
		String workspaceId = workspaceIdFrom(mvc.post().uri(ME).with(ana()).exchange());

		assertThat(mvc.get().uri(WORKSPACES).with(ana())).hasStatus(HttpStatus.OK)
			.bodyJson()
			.hasPathSatisfying("$.items.length()", size -> assertThat(size).asNumber().isEqualTo(1))
			.hasPathSatisfying("$.items[0].id", id -> assertThat(id).asString().isEqualTo(workspaceId))
			.hasPathSatisfying("$.items[0].name", name -> assertThat(name).asString().isEqualTo("Pessoal"))
			.hasPathSatisfying("$.items[0].kind", kind -> assertThat(kind).asString().isEqualTo("PERSONAL"))
			.hasPathSatisfying("$.items[0].baseCurrency", cur -> assertThat(cur).asString().isEqualTo("BRL"))
			.hasPathSatisfying("$.items[0].role", role -> assertThat(role).asString().isEqualTo("OWNER"))
			.hasPathSatisfying("$.items[0].createdAt", at -> assertThat(at).asString().endsWith("Z"));
	}

	@Test
	void getsOwnWorkspaceById() throws Exception {
		String workspaceId = workspaceIdFrom(mvc.post().uri(ME).with(ana()).exchange());

		assertThat(mvc.get().uri(WORKSPACES + "/{id}", workspaceId).with(ana())).hasStatus(HttpStatus.OK)
			.bodyJson()
			.hasPathSatisfying("$.id", id -> assertThat(id).asString().isEqualTo(workspaceId))
			.hasPathSatisfying("$.role", role -> assertThat(role).asString().isEqualTo("OWNER"));
	}

	// --- isolamento ---

	@Test
	void userCannotSeeAnotherUsersWorkspace() throws Exception {
		String anasWorkspace = workspaceIdFrom(mvc.post().uri(ME).with(ana()).exchange());
		String bobsWorkspace = workspaceIdFrom(mvc.post().uri(ME).with(bob()).exchange());

		assertThat(mvc.get().uri(WORKSPACES + "/{id}", anasWorkspace).with(bob())).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("WORKSPACE_NOT_FOUND"))
			.hasPathSatisfying("$.traceId", traceId -> assertThat(traceId).asString().isNotBlank());

		assertThat(mvc.get().uri(WORKSPACES).with(bob())).bodyJson()
			.hasPathSatisfying("$.items.length()", size -> assertThat(size).asNumber().isEqualTo(1))
			.hasPathSatisfying("$.items[0].id", id -> assertThat(id).asString().isEqualTo(bobsWorkspace));
	}

	@Test
	void foreignAndUnknownWorkspacesAreIndistinguishable() throws Exception {
		String anasWorkspace = workspaceIdFrom(mvc.post().uri(ME).with(ana()).exchange());
		mvc.post().uri(ME).with(bob()).exchange();

		MvcTestResult foreign = mvc.get().uri(WORKSPACES + "/{id}", anasWorkspace).with(bob()).exchange();
		MvcTestResult unknown = mvc.get().uri(WORKSPACES + "/{id}", UUID.randomUUID()).with(bob()).exchange();

		assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(unknown).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(withoutTraceId(foreign)).isEqualTo(withoutTraceId(unknown));
	}

	// --- erros ---

	@Test
	void requiresAuthentication() {
		assertThat(mvc.get().uri(WORKSPACES)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.get().uri(WORKSPACES + "/{id}", UUID.randomUUID())).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void requiresProvisionedUser() {
		assertThat(mvc.get().uri(WORKSPACES).with(ana())).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("USER_NOT_PROVISIONED"));
	}

	@Test
	void disabledUserIsForbidden() throws Exception {
		String workspaceId = workspaceIdFrom(mvc.post().uri(ME).with(ana()).exchange());
		jdbc.update("update users set status = 'DISABLED' where oidc_subject = ?", "sub-ana");

		assertThat(mvc.get().uri(WORKSPACES).with(ana())).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.get().uri(WORKSPACES + "/{id}", workspaceId).with(ana())).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void malformedWorkspaceIdIsBadRequest() {
		mvc.post().uri(ME).with(ana()).exchange();

		assertThat(mvc.get().uri(WORKSPACES + "/not-a-uuid").with(ana())).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("MALFORMED_REQUEST"));
	}

	// --- helpers ---

	private static RequestPostProcessor ana() {
		return identity("sub-ana", "ana@example.com");
	}

	private static RequestPostProcessor bob() {
		return identity("sub-bob", "bob@example.com");
	}

	private static RequestPostProcessor identity(String subject, String email) {
		return jwt().jwt(token -> token.subject(subject).claim("iss", ISSUER).claim("email", email).claim("name", "Nome"));
	}

	private static String workspaceIdFrom(MvcTestResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.workspaceId");
	}

	private static String withoutTraceId(MvcTestResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8).replaceAll("\"traceId\":\"[^\"]*\"", "");
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

}
