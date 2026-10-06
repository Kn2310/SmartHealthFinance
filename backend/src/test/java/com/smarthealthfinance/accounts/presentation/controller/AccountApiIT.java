package com.smarthealthfinance.accounts.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@ExtendWith(OutputCaptureExtension.class)
class AccountApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";

    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";

    private static final String ACCOUNT = ACCOUNTS + "/{accountId}";

    @Autowired
    JdbcTemplate jdbc;

    private String anasWorkspace;

    private String bobsWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        bobsWorkspace = read(mvc.post().uri(ME).with(bob()).exchange(), "$.workspaceId").toString();
    }

    // --- criação ---

    @Test
    void createsAccount() throws Exception {
        MvcTestResult result = create(ana(), anasWorkspace, """
                {"name": "  Banco Aurora ", "type": "CHECKING", "institutionName": "Banco Aurora", "includedInTotal": true}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.workspaceId", v -> assertThat(v).asString().isEqualTo(anasWorkspace))
                .hasPathSatisfying("$.name", v -> assertThat(v).asString().isEqualTo("Banco Aurora"))
                .hasPathSatisfying("$.type", v -> assertThat(v).asString().isEqualTo("CHECKING"))
                .hasPathSatisfying("$.institutionName", v -> assertThat(v).asString().isEqualTo("Banco Aurora"))
                .hasPathSatisfying("$.currency", v -> assertThat(v).asString().isEqualTo("BRL"))
                .hasPathSatisfying("$.status", v -> assertThat(v).asString().isEqualTo("ACTIVE"))
                .hasPathSatisfying("$.createdAt", v -> assertThat(v).asString().endsWith("Z"));
        String accountId = idFrom(result);
        assertThat(UUID.fromString(accountId).version()).isEqualTo(7);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/workspaces/" + anasWorkspace + "/accounts/" + accountId);
        assertThat(read(result, "$.includedInTotal")).isEqualTo(true);
        assertThat(countAccounts()).isEqualTo(1);
    }

    @Test
    void optionalFieldsHaveDefaults() throws Exception {
        MvcTestResult result = create(ana(), anasWorkspace, """
                {"name": "Carteira", "type": "OTHER"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(read(result, "$.institutionName")).isNull();
        assertThat(read(result, "$.includedInTotal")).isEqualTo(true);
    }

    @Test
    void rejectsBlankName() {
        assertThat(create(ana(), anasWorkspace, """
                {"name": "  ", "type": "CHECKING"}
                """)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo("name"));
        assertThat(countAccounts()).isZero();
    }

    @Test
    void rejectsUnknownType() {
        assertThat(create(ana(), anasWorkspace, """
                {"name": "Cartão", "type": "CREDIT_CARD"}
                """)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo("type"))
                .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo("INVALID"));
        assertThat(countAccounts()).isZero();
    }

    // --- consulta ---

    @Test
    void listsActiveAccountsAndOptionallyArchived() throws Exception {
        create(ana(), anasWorkspace, body("Banco Aurora", "CHECKING"));
        String reserva = idFrom(create(ana(), anasWorkspace, body("Banco Norte", "SAVINGS")));
        assertThat(mvc.post().uri(ACCOUNT + "/archive", anasWorkspace, reserva).with(ana())).hasStatus(HttpStatus.OK);

        assertThat(mvc.get().uri(ACCOUNTS, anasWorkspace).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.items[0].name", n -> assertThat(n).asString().isEqualTo("Banco Aurora"));

        assertThat(mvc.get().uri(ACCOUNTS, anasWorkspace).param("includeArchived", "true").with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(2));
    }

    @Test
    void emptyWorkspaceListsNoAccounts() {
        assertThat(mvc.get().uri(ACCOUNTS, anasWorkspace).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(0));
    }

    // --- edição ---

    @Test
    void updatesAccount() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, body("Banco Aurora", "CHECKING")));

        assertThat(update(ana(), anasWorkspace, id, """
                {"name": "Reserva", "type": "SAVINGS", "institutionName": "Banco Norte", "includedInTotal": false}
                """)).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.name", v -> assertThat(v).asString().isEqualTo("Reserva"))
                .hasPathSatisfying("$.type", v -> assertThat(v).asString().isEqualTo("SAVINGS"))
                .hasPathSatisfying("$.institutionName", v -> assertThat(v).asString().isEqualTo("Banco Norte"));

        MvcTestResult reloaded = mvc.get().uri(ACCOUNT, anasWorkspace, id).with(ana()).exchange();
        assertThat(reloaded).hasStatus(HttpStatus.OK);
        assertThat(read(reloaded, "$.name")).isEqualTo("Reserva");
        assertThat(read(reloaded, "$.includedInTotal")).isEqualTo(false);
    }

    @Test
    void updateRequiresIncludedInTotal() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, body("Banco Aurora", "CHECKING")));

        assertThat(update(ana(), anasWorkspace, id, """
                {"name": "Reserva", "type": "SAVINGS"}
                """)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo("includedInTotal"));
    }

    @Test
    void archivedAccountIsReadOnlyUntilReactivated() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, body("Banco Aurora", "CHECKING")));

        assertThat(mvc.post().uri(ACCOUNT + "/archive", anasWorkspace, id).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.status", s -> assertThat(s).asString().isEqualTo("ARCHIVED"));

        assertThat(update(ana(), anasWorkspace, id, body("Novo nome", "OTHER"))).hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("ACCOUNT_ARCHIVED"));

        assertThat(mvc.post().uri(ACCOUNT + "/reactivate", anasWorkspace, id).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.status", s -> assertThat(s).asString().isEqualTo("ACTIVE"));

        assertThat(update(ana(), anasWorkspace, id, body("Novo nome", "OTHER"))).hasStatus(HttpStatus.OK);
    }

    // --- isolamento ---

    @Test
    void userCannotTouchAccountsOfAnotherWorkspace() throws Exception {
        String id = idFrom(create(ana(), anasWorkspace, body("Banco Aurora", "CHECKING")));

        assertWorkspaceNotFound(mvc.get().uri(ACCOUNTS, anasWorkspace).with(bob()).exchange());
        assertWorkspaceNotFound(create(bob(), anasWorkspace, body("Invasora", "CHECKING")));
        assertWorkspaceNotFound(mvc.get().uri(ACCOUNT, anasWorkspace, id).with(bob()).exchange());
        assertWorkspaceNotFound(update(bob(), anasWorkspace, id, body("Invasora", "OTHER")));
        assertWorkspaceNotFound(mvc.post().uri(ACCOUNT + "/archive", anasWorkspace, id).with(bob()).exchange());

        assertThat(countAccounts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from accounts", String.class)).isEqualTo("Banco Aurora");
        assertThat(jdbc.queryForObject("select status from accounts", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void foreignAndUnknownAccountsAreIndistinguishable() throws Exception {
        String anasAccount = idFrom(create(ana(), anasWorkspace, body("Banco Aurora", "CHECKING")));

        MvcTestResult foreign = mvc.get().uri(ACCOUNT, bobsWorkspace, anasAccount).with(bob()).exchange();
        MvcTestResult unknown = mvc.get().uri(ACCOUNT, bobsWorkspace, UUID.randomUUID()).with(bob()).exchange();

        assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThat(withoutTraceId(foreign)).isEqualTo(withoutTraceId(unknown));
    }

    @Test
    void listNeverMixesWorkspaces() throws Exception {
        create(ana(), anasWorkspace, body("Banco Aurora", "CHECKING"));
        String bobsAccount = idFrom(create(bob(), bobsWorkspace, body("Conta do Bob", "CHECKING")));

        assertThat(mvc.get().uri(ACCOUNTS, bobsWorkspace).with(bob())).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", s -> assertThat(s).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.items[0].id", i -> assertThat(i).asString().isEqualTo(bobsAccount));
    }

    // --- erros e privacidade ---

    @Test
    void requiresAuthentication() {
        assertThat(mvc.get().uri(ACCOUNTS, anasWorkspace)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post()
                .uri(ACCOUNTS, anasWorkspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Conta", "CHECKING"))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedAccountIdIsBadRequest() {
        assertThat(mvc.get().uri(ACCOUNTS + "/not-a-uuid", anasWorkspace).with(ana()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("MALFORMED_REQUEST"));
    }

    @Test
    void creationLogsOnlyIds(CapturedOutput output) {
        assertThat(create(ana(), anasWorkspace, body("Conta Secreta Pessoal", "CHECKING")))
                .hasStatus(HttpStatus.CREATED);

        assertThat(output).contains("Account created").doesNotContain("Conta Secreta Pessoal");
    }

    // --- helpers ---

    private MvcTestResult create(RequestPostProcessor who, String workspaceId, String json) {
        return mvc.post().uri(ACCOUNTS, workspaceId).with(who).contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
    }

    private MvcTestResult update(RequestPostProcessor who, String workspaceId, String accountId, String json) {
        return mvc.put().uri(ACCOUNT, workspaceId, accountId).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(json).exchange();
    }

    private static String body(String name, String type) {
        return """
                {"name": "%s", "type": "%s", "includedInTotal": true}
                """.formatted(name, type);
    }

    private static void assertWorkspaceNotFound(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
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

    private static String idFrom(MvcTestResult result) throws Exception {
        return read(result, "$.id").toString();
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private static String withoutTraceId(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\"traceId\":\"[^\"]*\"", "");
    }

    private int countAccounts() {
        return jdbc.queryForObject("select count(*) from accounts", Integer.class);
    }
}
