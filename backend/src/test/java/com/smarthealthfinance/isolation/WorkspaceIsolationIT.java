package com.smarthealthfinance.isolation;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Suíte de isolamento entre Workspaces (ADR-0003 §6 — regra do 404; backlog M1 "isolation tests").
 *
 * <ul>
 * <li>{@link #FOREIGN_WORKSPACE}: Bob tenta cada operação sob {@code /workspaces/{id}} no Workspace da Ana. Toda resposta
 * é {@code 404 WORKSPACE_NOT_FOUND}, idêntica à de um Workspace inexistente, sem dados da Ana no corpo e sem alterar
 * nenhuma tabela.</li>
 * <li>{@link #FOREIGN_REFERENCE}: Ana usa ids do Bob dentro do próprio Workspace. A resposta é a definida nos ADRs,
 * idêntica à de um id inexistente, e os dados do Bob não mudam.</li>
 * <li>{@link #everyWorkspaceScopedEndpointIsCovered()}: endpoint novo sob {@code /workspaces/{workspaceId}} sem caso
 * em {@link #FOREIGN_WORKSPACE} quebra a suíte.</li>
 * </ul>
 */
class WorkspaceIsolationIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String WORKSPACE_SCOPE = "/api/v1/workspaces/{workspaceId}";
    private static final String ACCOUNTS = WORKSPACE_SCOPE + "/accounts";
    private static final String ACCOUNT = ACCOUNTS + "/{accountId}";
    private static final String TRANSACTIONS = WORKSPACE_SCOPE + "/transactions";
    private static final String TRANSACTION = TRANSACTIONS + "/{transactionId}";
    private static final String IMPORTS = WORKSPACE_SCOPE + "/imports";
    private static final String IMPORT = IMPORTS + "/{importId}";

    /** Dados da Ana que nunca podem aparecer numa resposta para o Bob. */
    private static final String ANAS_ACCOUNT_NAME = "Conta Sigilosa Ana";
    private static final String ANAS_DESCRIPTION = "Clínica Sigilosa Ana";
    private static final String ANAS_AMOUNT = "4321.98";

    private static final String STATEMENT = """
            data;descricao;valor;id
            01/09/2026;Salário Fictício;4.500,00;iso-1
            02/09/2026;Mercado Fictício;-86,40;iso-2
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private Fixture ana;

    private Fixture bob;

    /** Ids de um Workspace semeado: uma conta ativa, uma segunda conta, uma arquivada, transações POSTED/PENDING e um preview de importação. */
    record Fixture(String workspace, String account, String otherAccount, String archivedAccount, String posted,
                   String pending, String importId) {

        List<String> ids() {
            return List.of(workspace, account, otherAccount, archivedAccount, posted, pending, importId);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        ana = seed(ana(), ANAS_ACCOUNT_NAME, ANAS_DESCRIPTION, ANAS_AMOUNT);
        bob = seed(bob(), "Conta do Bob", "Compra do Bob", "10.00");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. Bob no Workspace da Ana: toda operação sob /workspaces/{id}
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Uma operação sob {@code /workspaces/{workspaceId}}: {@code template} é o mapeamento exato do controller, usado
     * também na checagem de cobertura; {@code pathIds} preenche as variáveis depois do Workspace.
     */
    record Op(String name, HttpMethod method, String template, Function<Fixture, Object[]> pathIds, Body body) {

        @Override
        public String toString() {
            return name;
        }

        String key() {
            return method.name() + " " + template;
        }
    }

    @FunctionalInterface
    interface Body {
        AbstractMockHttpServletRequestBuilder<?> apply(MockMvcTester.MockMvcRequestBuilder request, Fixture target);
    }

    static final Body NONE = (request, target) -> request;

    static final List<Op> FOREIGN_WORKSPACE = List.of(
            op("workspace: consultar", HttpMethod.GET, WORKSPACE_SCOPE, f -> new Object[0], NONE),

            op("accounts: listar", HttpMethod.GET, ACCOUNTS, f -> new Object[0], NONE),
            op("accounts: criar", HttpMethod.POST, ACCOUNTS, f -> new Object[0], json(f -> """
                    {"name": "Invasora", "type": "CHECKING"}
                    """)),
            op("accounts: consultar", HttpMethod.GET, ACCOUNT, f -> ids(f.account()), NONE),
            op("accounts: editar", HttpMethod.PUT, ACCOUNT, f -> ids(f.account()), json(f -> """
                    {"name": "Invasora", "type": "OTHER", "includedInTotal": false}
                    """)),
            op("accounts: arquivar", HttpMethod.POST, ACCOUNT + "/archive", f -> ids(f.account()), NONE),
            op("accounts: reativar", HttpMethod.POST, ACCOUNT + "/reactivate", f -> ids(f.archivedAccount()), NONE),

            op("transactions: listar", HttpMethod.GET, TRANSACTIONS, f -> new Object[0],
                    (request, f) -> request.param("accountId", f.account())),
            op("transactions: criar", HttpMethod.POST, TRANSACTIONS, f -> new Object[0],
                    idempotent(json(f -> expense(f.account(), "1.00", "Invasora")))),
            op("transactions: consultar", HttpMethod.GET, TRANSACTION, f -> ids(f.posted()), NONE),
            op("transactions: editar", HttpMethod.PUT, TRANSACTION, f -> ids(f.posted()), json(f -> """
                    {"description": "Invasora"}
                    """)),
            op("transactions: efetivar", HttpMethod.POST, TRANSACTION + "/post", f -> ids(f.pending()), NONE),
            op("transactions: cancelar", HttpMethod.POST, TRANSACTION + "/cancel", f -> ids(f.pending()), NONE),
            op("transactions: estornar", HttpMethod.POST, TRANSACTION + "/reverse", f -> ids(f.posted()), NONE),

            op("overview: consultar", HttpMethod.GET, WORKSPACE_SCOPE + "/overview", f -> new Object[0], NONE),

            op("imports: upload", HttpMethod.POST, IMPORTS, f -> new Object[0],
                    (request, f) -> request.multipart().file(statement()).param("accountId", f.account())
                            .header("Idempotency-Key", newKey())),
            op("imports: listar", HttpMethod.GET, IMPORTS, f -> new Object[0], NONE),
            op("imports: consultar", HttpMethod.GET, IMPORT, f -> ids(f.importId()), NONE),
            op("imports: linhas", HttpMethod.GET, IMPORT + "/records", f -> ids(f.importId()), NONE),
            op("imports: confirmar", HttpMethod.POST, IMPORT + "/confirm", f -> ids(f.importId()), NONE),
            op("imports: cancelar", HttpMethod.POST, IMPORT + "/cancel", f -> ids(f.importId()), NONE));

    static Stream<Op> foreignWorkspaceOperations() {
        return FOREIGN_WORKSPACE.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("foreignWorkspaceOperations")
    void foreignWorkspaceIsNotFoundAndIndistinguishableFromUnknown(Op op) throws Exception {
        String before = snapshot();

        MvcTestResult foreign = exchange(op, ana.workspace(), ana, bob());
        MvcTestResult unknown = exchange(op, UUID.randomUUID().toString(), ana, bob());

        assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
        assertThat(withoutTraceId(foreign)).as("alheio × inexistente").isEqualTo(withoutTraceId(unknown));
        assertNoLeak(foreign, ana, ANAS_ACCOUNT_NAME, ANAS_DESCRIPTION, ANAS_AMOUNT);
        assertThat(snapshot()).as("nenhuma tabela pode mudar").isEqualTo(before);
    }

    /** Falha quando surgir endpoint sob /workspaces/{workspaceId} sem caso em FOREIGN_WORKSPACE (ou caso sem endpoint). */
    @Test
    void everyWorkspaceScopedEndpointIsCovered() {
        Set<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()
                        .filter(p -> p.equals(WORKSPACE_SCOPE) || p.startsWith(WORKSPACE_SCOPE + "/"))
                        .flatMap(p -> info.getMethodsCondition().getMethods().stream().map(m -> m.name() + " " + p)))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> covered = FOREIGN_WORKSPACE.stream().map(Op::key).collect(Collectors.toCollection(TreeSet::new));

        assertThat(mapped).as("endpoints mapeados").isNotEmpty();
        assertThat(covered).as("adicione o endpoint novo em FOREIGN_WORKSPACE").containsExactlyElementsOf(mapped);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2. Ana no próprio Workspace usando ids do Bob
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code foreignId} escolhe o id do Bob; o mesmo pedido com um UUID aleatório precisa responder igual. */
    record CrossOp(String name, HttpStatus status, String code, Function<Fixture, String> foreignId, CrossRequest request) {

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface CrossRequest {
        AbstractMockHttpServletRequestBuilder<?> build(MockMvcTester mvc, Fixture own, String foreignId);
    }

    static final List<CrossOp> FOREIGN_REFERENCE = List.of(
            // ADR-0004 §2: conta de outro Workspace → 404 ACCOUNT_NOT_FOUND.
            cross("accounts: consultar conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.get().uri(ACCOUNT, own.workspace(), id)),
            cross("accounts: editar conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.put().uri(ACCOUNT, own.workspace(), id).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Invasora", "type": "OTHER", "includedInTotal": false}
                                    """)),
            cross("accounts: arquivar conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(ACCOUNT + "/archive", own.workspace(), id)),
            cross("accounts: reativar conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::archivedAccount,
                    (mvc, own, id) -> mvc.post().uri(ACCOUNT + "/reactivate", own.workspace(), id)),

            // ADR-0005 §9: conta (origem ou destino) precisa existir no Workspace autorizado → 404 ACCOUNT_NOT_FOUND.
            cross("transactions: lançar na conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> createTransaction(mvc, own, expense(id, "1.00", "Na conta alheia"))),
            cross("transactions: transferir para conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                    Fixture::account, (mvc, own, id) -> createTransaction(mvc, own, """
                            {"type": "TRANSFER", "accountId": "%s", "destinationAccountId": "%s",
                             "amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "2026-09-22",
                             "description": "Para conta alheia"}
                            """.formatted(own.account(), id))),
            // ADR-0005 §6: o original do reembolso precisa ser do mesmo Workspace; alheio = inexistente.
            cross("transactions: reembolsar transação alheia", HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    Fixture::posted, (mvc, own, id) -> createTransaction(mvc, own, """
                            {"type": "REFUND", "accountId": "%s", "refundOfTransactionId": "%s",
                             "amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "2026-09-22",
                             "description": "Reembolso alheio"}
                            """.formatted(own.account(), id))),
            // ADR-0005 §10: transação de outro Workspace → 404 TRANSACTION_NOT_FOUND.
            cross("transactions: consultar transação alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::posted, (mvc, own, id) -> mvc.get().uri(TRANSACTION, own.workspace(), id)),
            cross("transactions: editar transação alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::posted, (mvc, own, id) -> mvc.put().uri(TRANSACTION, own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"description": "Invasora"}
                                    """)),
            cross("transactions: efetivar transação alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::pending, (mvc, own, id) -> mvc.post().uri(TRANSACTION + "/post", own.workspace(), id)),
            cross("transactions: cancelar transação alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::pending, (mvc, own, id) -> mvc.post().uri(TRANSACTION + "/cancel", own.workspace(), id)),
            cross("transactions: estornar transação alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::posted, (mvc, own, id) -> mvc.post().uri(TRANSACTION + "/reverse", own.workspace(), id)),
            // ADR-0005 §1 (adendo): filtro por conta alheia = conta inexistente → lista vazia.
            cross("transactions: filtrar por conta alheia", HttpStatus.OK, null, Fixture::account,
                    (mvc, own, id) -> mvc.get().uri(TRANSACTIONS, own.workspace()).param("accountId", id)),

            // ADR-0009 §4: conta de destino ativa e do mesmo Workspace; §17: batch alheio → 404 IMPORT_NOT_FOUND.
            cross("imports: upload na conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(IMPORTS, own.workspace()).multipart().file(statement())
                            .param("accountId", id).header("Idempotency-Key", newKey())),
            cross("imports: consultar importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.get().uri(IMPORT, own.workspace(), id)),
            cross("imports: linhas de importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.get().uri(IMPORT + "/records", own.workspace(), id)),
            cross("imports: confirmar importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.post().uri(IMPORT + "/confirm", own.workspace(), id)),
            cross("imports: cancelar importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.post().uri(IMPORT + "/cancel", own.workspace(), id)));

    static Stream<CrossOp> foreignReferenceOperations() {
        return FOREIGN_REFERENCE.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("foreignReferenceOperations")
    void foreignIdInsideOwnWorkspaceBehavesAsUnknown(CrossOp op) throws Exception {
        String before = snapshot();

        MvcTestResult foreign = mvc.perform(op.request().build(mvc, ana, op.foreignId().apply(bob)).with(ana()));
        MvcTestResult unknown = mvc.perform(op.request().build(mvc, ana, UUID.randomUUID().toString()).with(ana()));

        assertThat(foreign).hasStatus(op.status());
        if (op.code() != null) {
            assertThat(foreign).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo(op.code()));
        } else {
            assertThat(foreign).bodyJson()
                    .hasPathSatisfying("$.totalItems", t -> assertThat(t).asNumber().isEqualTo(0));
        }
        assertThat(withoutTraceId(foreign)).as("alheio × inexistente").isEqualTo(withoutTraceId(unknown));
        assertNoLeak(foreign, bob, "Conta do Bob", "Compra do Bob");
        assertThat(snapshot()).as("nenhuma tabela pode mudar").isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------------

    private MvcTestResult exchange(Op op, String workspace, Fixture target, RequestPostProcessor who) {
        Object[] vars = Stream.concat(Stream.of(workspace), Stream.of(op.pathIds().apply(target))).toArray();
        return mvc.perform(op.body().apply(mvc.method(op.method()).uri(op.template(), vars), target).with(who));
    }

    /** Workspace provisionado com contas, transações (POSTED e PENDING) e um preview de importação. */
    private Fixture seed(RequestPostProcessor who, String accountName, String description, String amount)
            throws Exception {
        String workspace = read(mvc.post().uri(ME).with(who).exchange(), "$.workspaceId");
        String account = createAccount(who, workspace, accountName);
        String other = createAccount(who, workspace, accountName + " 2");
        String archived = createAccount(who, workspace, accountName + " arquivada");
        assertThat(mvc.post().uri(ACCOUNT + "/archive", workspace, archived).with(who)).hasStatus(HttpStatus.OK);
        String posted = createTransaction(who, workspace, expense(account, amount, description));
        String pending = createTransaction(who, workspace, """
                {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                 "occurredOn": "2026-09-23", "description": "%s", "status": "PENDING"}
                """.formatted(account, amount, description));
        MvcTestResult upload = mvc.post().uri(IMPORTS, workspace).multipart().file(statement())
                .param("accountId", account).header("Idempotency-Key", newKey()).with(who).exchange();
        assertThat(upload).hasStatus(HttpStatus.CREATED);
        return new Fixture(workspace, account, other, archived, posted, pending, read(upload, "$.id"));
    }

    private String createAccount(RequestPostProcessor who, String workspace, String name) throws Exception {
        MvcTestResult result = mvc.post().uri(ACCOUNTS, workspace).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "type": "CHECKING"}
                        """.formatted(name)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id");
    }

    private String createTransaction(RequestPostProcessor who, String workspace, String json) throws Exception {
        MvcTestResult result = mvc.perform(createTransaction(mvc, workspace, json).with(who));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, "$.id");
    }

    private static AbstractMockHttpServletRequestBuilder<?> createTransaction(MockMvcTester mvc, Fixture own,
                                                                              String json) {
        return createTransaction(mvc, own.workspace(), json);
    }

    private static AbstractMockHttpServletRequestBuilder<?> createTransaction(MockMvcTester mvc, String workspace,
                                                                              String json) {
        return mvc.post().uri(TRANSACTIONS, workspace).header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /**
     * Todas as tabelas do sistema (linhas ordenadas). Uma tentativa recusada não pode mudar nada — nem consumir
     * chave de idempotência nem gravar evento na outbox.
     */
    private String snapshot() {
        return Stream.of("users", "workspaces", "workspace_memberships", "accounts", "transactions",
                        "transaction_idempotency_keys", "import_batches", "import_records", "imported_transaction_keys",
                        "outbox_events", "processed_events")
                .map(table -> table + "=" + jdbc.queryForObject(
                        "select coalesce(string_agg(t::text, '|' order by t::text), '') from " + table + " t",
                        String.class))
                .collect(Collectors.joining("\n"));
    }

    private static void assertNoLeak(MvcTestResult result, Fixture owner, String... values) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String id : owner.ids()) {
            assertThat(body).as("id do outro Workspace no corpo").doesNotContain(id);
        }
        for (String value : values) {
            assertThat(body).as("dado do outro Workspace no corpo").doesNotContain(value);
        }
    }

    private static Op op(String name, HttpMethod method, String template, Function<Fixture, Object[]> pathIds,
                         Body body) {
        return new Op(name, method, template, pathIds, body);
    }

    private static CrossOp cross(String name, HttpStatus status, String code, Function<Fixture, String> foreignId,
                                 CrossRequest request) {
        return new CrossOp(name, status, code, foreignId, request);
    }

    private static Object[] ids(String... ids) {
        return ids;
    }

    private static Body json(Function<Fixture, String> content) {
        return (request, target) -> request.contentType(MediaType.APPLICATION_JSON).content(content.apply(target));
    }

    /** Chave nova por requisição: a comparação com o Workspace inexistente não pode virar replay. */
    private static Body idempotent(Body body) {
        return (request, target) -> body.apply(request.header("Idempotency-Key", newKey()), target);
    }

    private static String newKey() {
        return "iso-" + UUID.randomUUID();
    }

    private static String expense(String accountId, String amount, String description) {
        return """
                {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                 "occurredOn": "2026-09-22", "description": "%s"}
                """.formatted(accountId, amount, description);
    }

    private static MockMultipartFile statement() {
        return new MockMultipartFile("file", "extrato.csv", "text/csv", STATEMENT.getBytes(StandardCharsets.UTF_8));
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

    private static String read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path).toString();
    }

    private static String withoutTraceId(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\"traceId\":\"[^\"]*\"", "");
    }
}
