package com.smarthealthfinance.isolation;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.NewMessage;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.port.FeedbackStore;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.FeedbackRating;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.UuidV7;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private static final String INSIGHTS = WORKSPACE_SCOPE + "/insights";
    private static final String INSIGHT = INSIGHTS + "/{insightId}";
    private static final String CARDS = WORKSPACE_SCOPE + "/cards";
    private static final String CARD = CARDS + "/{cardId}";
    private static final String CARD_INVOICE = CARD + "/invoices/{dueMonth}";
    private static final String FORECAST = WORKSPACE_SCOPE + "/forecast";
    private static final String EXPENSES = WORKSPACE_SCOPE + "/recurring-expenses";
    private static final String EXPENSE = EXPENSES + "/{seriesId}";
    private static final String INCOMES = WORKSPACE_SCOPE + "/recurring-incomes";
    private static final String INCOME = INCOMES + "/{seriesId}";
    private static final String GOALS = WORKSPACE_SCOPE + "/goals";
    private static final String GOAL = GOALS + "/{goalId}";
    private static final String CONSENTS = WORKSPACE_SCOPE + "/consents";
    private static final String COPILOT = WORKSPACE_SCOPE + "/copilot";
    private static final String COPILOT_QUESTIONS = COPILOT + "/questions";
    private static final String COPILOT_CONVERSATIONS = COPILOT + "/conversations";
    private static final String COPILOT_CONVERSATION = COPILOT_CONVERSATIONS + "/{conversationId}";
    private static final String COPILOT_FEEDBACK = COPILOT_QUESTIONS + "/{requestId}/feedback";
    private static final String COPILOT_FEEDBACK_JSON = """
            {"rating": "NOT_HELPFUL", "reason": "OTHER"}
            """;
    private static final String COPILOT_QUESTION_JSON = """
            {"question": "Qual é o meu saldo?"}
            """;
    private static final String GOAL_UPDATE_JSON = """
            {"name": "Invasor", "targetAmount": {"amount": "10.00", "currency": "BRL"},
             "plannedMonthly": {"amount": "1.00", "currency": "BRL"}}
            """;
    private static final String GOAL_PLAN_JSON = """
            {"plannedMonthly": {"amount": "2.00", "currency": "BRL"}}
            """;
    private static final String SIMULATION_JSON = """
            {"scenarios": [{"monthlyAmount": {"amount": "5.00", "currency": "BRL"}}]}
            """;
    private static final String ADJUSTMENT_JSON = """
            {"expectedAmount": {"amount": "1.00", "currency": "BRL"}, "expectedDay": 3}
            """;
    private static final String CARD_JSON = """
            {"nickname": "Invasor", "brand": "VISA", "last4": "0000", "limit": {"amount": "100.00", "currency": "BRL"},
             "closingDay": 28, "dueDay": 5}
            """;
    /** O Workspace é autorizado antes de procurar o insight: qualquer id serve para o caso "Workspace alheio". */
    private static final String SOME_INSIGHT = "00000000-0000-7000-8000-00000000abcd";

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
    ConversationStore conversations;

    @Autowired
    FeedbackStore copilotFeedback;

    @Autowired
    AiUsageLedger aiUsage;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private Fixture ana;

    private Fixture bob;

    /** Ids de um Workspace semeado: uma conta ativa, uma segunda conta, uma arquivada, transações POSTED/PENDING e um preview de importação. */
    record Fixture(String workspace, String account, String otherAccount, String archivedAccount, String posted,
                   String pending, String importId, String card, String purchase, String expenseSeries,
                   String incomeSeries, String goal, String conversation, String copilotRequest) {

        List<String> ids() {
            return List.of(workspace, account, otherAccount, archivedAccount, posted, pending, importId, card,
                    purchase, expenseSeries, incomeSeries, goal, conversation, copilotRequest);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        ana = seed(ana(), ANAS_ACCOUNT_NAME, ANAS_DESCRIPTION, ANAS_AMOUNT);
        bob = seed(bob(), "Conta do Bob", "Compra do Bob", "10.00");
        // Os eventos do seed (transaction.created) são processados de forma assíncrona: o snapshot espera.
        IdentityTables.awaitQuiescence(jdbc);
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
            op("transactions: resumo", HttpMethod.GET, TRANSACTIONS + "/summary", f -> new Object[0],
                    (request, f) -> request.param("accountId", f.account())),
            op("transactions: consultar", HttpMethod.GET, TRANSACTION, f -> ids(f.posted()), NONE),
            op("transactions: editar", HttpMethod.PUT, TRANSACTION, f -> ids(f.posted()), json(f -> """
                    {"description": "Invasora"}
                    """)),
            op("transactions: efetivar", HttpMethod.POST, TRANSACTION + "/post", f -> ids(f.pending()), NONE),
            op("transactions: cancelar", HttpMethod.POST, TRANSACTION + "/cancel", f -> ids(f.pending()), NONE),
            op("transactions: estornar", HttpMethod.POST, TRANSACTION + "/reverse", f -> ids(f.posted()), NONE),
            op("transactions: marcar como reembolso", HttpMethod.POST, TRANSACTION + "/mark-as-refund",
                    f -> ids(f.posted()), NONE),
            op("transactions: categorizar", HttpMethod.PUT, TRANSACTION + "/category", f -> ids(f.posted()),
                    json(f -> """
                            {"categoryCode": "OTHER_EXPENSE"}
                            """)),

            op("transactions: categorizar pendentes", HttpMethod.POST, TRANSACTIONS + "/categorize-pending",
                    f -> new Object[0], NONE),
            op("categories: listar", HttpMethod.GET, WORKSPACE_SCOPE + "/categories", f -> new Object[0], NONE),

            op("analytics: status", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/status", f -> new Object[0], NONE),
            op("analytics: overview", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/overview", f -> new Object[0], NONE),
            op("analytics: spending", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/spending", f -> new Object[0], NONE),
            op("analytics: income", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/income", f -> new Object[0], NONE),
            op("analytics: categories", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/categories", f -> new Object[0], NONE),
            op("analytics: merchants", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/merchants", f -> new Object[0], NONE),
            op("analytics: evolution", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/evolution", f -> new Object[0], NONE),
            op("analytics: categoria", HttpMethod.GET, WORKSPACE_SCOPE + "/analytics/categories/{categoryCode}",
                    f -> new Object[] {"DINING_OUT"}, NONE),

            op("overview: consultar", HttpMethod.GET, WORKSPACE_SCOPE + "/overview", f -> new Object[0], NONE),

            op("health: resumo", HttpMethod.GET, WORKSPACE_SCOPE + "/health", f -> new Object[0], NONE),
            op("health: evolução", HttpMethod.GET, WORKSPACE_SCOPE + "/health/evolution", f -> new Object[0], NONE),
            op("health: método", HttpMethod.GET, WORKSPACE_SCOPE + "/health/method", f -> new Object[0], NONE),
            op("health: status", HttpMethod.GET, WORKSPACE_SCOPE + "/health/status", f -> new Object[0], NONE),

            op("forecast: status", HttpMethod.GET, WORKSPACE_SCOPE + "/forecast/status", f -> new Object[0], NONE),
            op("forecast: mês", HttpMethod.GET, FORECAST + "/month", f -> new Object[0], NONE),
            op("forecast: próxima receita", HttpMethod.GET, FORECAST + "/next-income", f -> new Object[0], NONE),
            op("forecast: próximos eventos", HttpMethod.GET, FORECAST + "/upcoming", f -> new Object[0], NONE),
            op("forecast: faturas", HttpMethod.GET, FORECAST + "/invoices", f -> new Object[0],
                    (request, f) -> request.param("cardId", f.card())),
            op("forecast: fluxo de caixa", HttpMethod.GET, FORECAST + "/cash-flow", f -> new Object[0], NONE),
            op("despesas recorrentes: listar", HttpMethod.GET, EXPENSES, f -> new Object[0], NONE),
            op("despesas recorrentes: detalhe", HttpMethod.GET, EXPENSE, f -> ids(f.expenseSeries()), NONE),
            op("despesas recorrentes: confirmar", HttpMethod.POST, EXPENSE + "/confirm", f -> ids(f.expenseSeries()), NONE),
            op("despesas recorrentes: ignorar", HttpMethod.POST, EXPENSE + "/ignore", f -> ids(f.expenseSeries()), NONE),
            op("despesas recorrentes: ajustar", HttpMethod.PUT, EXPENSE + "/adjustment", f -> ids(f.expenseSeries()),
                    json(f -> ADJUSTMENT_JSON)),
            op("despesas recorrentes: remover ajuste", HttpMethod.DELETE, EXPENSE + "/adjustment", f -> ids(f.expenseSeries()),
                    NONE),

            op("receitas recorrentes: listar", HttpMethod.GET, INCOMES, f -> new Object[0], NONE),
            op("receitas recorrentes: detalhe", HttpMethod.GET, INCOME, f -> ids(f.incomeSeries()), NONE),
            op("receitas recorrentes: confirmar", HttpMethod.POST, INCOME + "/confirm", f -> ids(f.incomeSeries()), NONE),
            op("receitas recorrentes: ignorar", HttpMethod.POST, INCOME + "/ignore", f -> ids(f.incomeSeries()), NONE),
            op("receitas recorrentes: ajustar", HttpMethod.PUT, INCOME + "/adjustment", f -> ids(f.incomeSeries()),
                    json(f -> ADJUSTMENT_JSON)),
            op("receitas recorrentes: remover ajuste", HttpMethod.DELETE, INCOME + "/adjustment", f -> ids(f.incomeSeries()),
                    NONE),


            op("insights: status", HttpMethod.GET, WORKSPACE_SCOPE + "/insights/status", f -> new Object[0], NONE),
            op("insights: listar", HttpMethod.GET, INSIGHTS, f -> new Object[0], NONE),
            op("insights: destaques", HttpMethod.GET, INSIGHTS + "/highlights", f -> new Object[0], NONE),
            op("insights: relacionados", HttpMethod.GET, INSIGHTS + "/related", f -> new Object[0],
                    (request, f) -> request.param("transactionId", f.posted())),
            op("insights: detalhe", HttpMethod.GET, INSIGHT, f -> ids(SOME_INSIGHT), NONE),
            op("insights: visto", HttpMethod.POST, INSIGHT + "/seen", f -> ids(SOME_INSIGHT), NONE),
            op("insights: dispensar", HttpMethod.POST, INSIGHT + "/dismiss", f -> ids(SOME_INSIGHT), NONE),
            op("insights: feedback", HttpMethod.PUT, INSIGHT + "/feedback", f -> ids(SOME_INSIGHT), json(f -> """
                    {"value": "NOT_USEFUL"}
                    """)),

            op("imports: upload", HttpMethod.POST, IMPORTS, f -> new Object[0],
                    (request, f) -> request.multipart().file(statement()).param("accountId", f.account())
                            .header("Idempotency-Key", newKey())),
            op("imports: listar", HttpMethod.GET, IMPORTS, f -> new Object[0], NONE),
            op("imports: consultar", HttpMethod.GET, IMPORT, f -> ids(f.importId()), NONE),
            op("imports: linhas", HttpMethod.GET, IMPORT + "/records", f -> ids(f.importId()), NONE),
            op("imports: confirmar", HttpMethod.POST, IMPORT + "/confirm", f -> ids(f.importId()), NONE),
            op("imports: cancelar", HttpMethod.POST, IMPORT + "/cancel", f -> ids(f.importId()), NONE),

            op("consents: listar", HttpMethod.GET, CONSENTS, f -> new Object[0], NONE),
            op("consents: revogar", HttpMethod.PUT, CONSENTS + "/{purpose}", f -> ids("COPILOT_DATA_USE"),
                    json(f -> """
                            {"granted": false}
                            """)),

            op("copilot: perguntar", HttpMethod.POST, COPILOT_QUESTIONS, f -> new Object[0],
                    idempotent(json(f -> COPILOT_QUESTION_JSON))),
            op("copilot: perguntar numa conversa", HttpMethod.POST, COPILOT_QUESTIONS, f -> new Object[0],
                    idempotent(json(f -> """
                            {"question": "E agora?", "conversationId": "%s"}
                            """.formatted(f.conversation())))),
            op("copilot: uso", HttpMethod.GET, COPILOT + "/usage", f -> new Object[0], NONE),
            op("copilot: listar conversas", HttpMethod.GET, COPILOT_CONVERSATIONS, f -> new Object[0], NONE),
            op("copilot: consultar conversa", HttpMethod.GET, COPILOT_CONVERSATION, f -> ids(f.conversation()), NONE),
            op("copilot: apagar conversa", HttpMethod.DELETE, COPILOT_CONVERSATION, f -> ids(f.conversation()), NONE),
            op("copilot: apagar todas as conversas", HttpMethod.DELETE, COPILOT_CONVERSATIONS, f -> new Object[0],
                    NONE),
            op("copilot: feedback", HttpMethod.PUT, COPILOT_FEEDBACK, f -> ids(f.copilotRequest()),
                    json(f -> COPILOT_FEEDBACK_JSON)),

            op("goals: listar", HttpMethod.GET, GOALS, f -> new Object[0], NONE),
            op("goals: criar", HttpMethod.POST, GOALS, f -> new Object[0], json(f -> goalJson(f.otherAccount(), null))),
            op("goals: aportes recentes", HttpMethod.GET, GOALS + "/recent-contributions", f -> new Object[0], NONE),
            op("goals: consultar", HttpMethod.GET, GOAL, f -> ids(f.goal()), NONE),
            op("goals: editar", HttpMethod.PUT, GOAL, f -> ids(f.goal()), json(f -> GOAL_UPDATE_JSON)),
            op("goals: aplicar plano", HttpMethod.PUT, GOAL + "/plan", f -> ids(f.goal()), json(f -> GOAL_PLAN_JSON)),
            op("goals: pausar", HttpMethod.POST, GOAL + "/pause", f -> ids(f.goal()), NONE),
            op("goals: retomar", HttpMethod.POST, GOAL + "/resume", f -> ids(f.goal()), NONE),
            op("goals: concluir", HttpMethod.POST, GOAL + "/complete", f -> ids(f.goal()), NONE),
            op("goals: arquivar", HttpMethod.POST, GOAL + "/archive", f -> ids(f.goal()), NONE),
            op("goals: registrar aporte", HttpMethod.POST, GOAL + "/contributions", f -> ids(f.goal()),
                    idempotent(json(f -> contributionJson(f.account())))),
            op("goals: histórico de aportes", HttpMethod.GET, GOAL + "/contributions", f -> ids(f.goal()), NONE),
            op("goals: simular", HttpMethod.POST, GOAL + "/simulations", f -> ids(f.goal()), json(f -> SIMULATION_JSON)),
            op("goals: prévia de criação", HttpMethod.POST, GOALS + "/simulations/preview", f -> new Object[0],
                    json(f -> previewJson(f.otherAccount()))),
            op("goals: projeção", HttpMethod.GET, GOAL + "/projection", f -> ids(f.goal()), NONE),

            op("cards: listar", HttpMethod.GET, CARDS, f -> new Object[0], NONE),
            op("cards: criar", HttpMethod.POST, CARDS, f -> new Object[0], json(f -> CARD_JSON)),
            op("cards: resumo", HttpMethod.GET, CARDS + "/summary", f -> new Object[0], NONE),
            op("cards: consultar", HttpMethod.GET, CARD, f -> ids(f.card()), NONE),
            op("cards: editar", HttpMethod.PUT, CARD, f -> ids(f.card()), json(f -> CARD_JSON)),
            op("cards: arquivar", HttpMethod.POST, CARD + "/archive", f -> ids(f.card()), NONE),
            op("cards: reativar", HttpMethod.POST, CARD + "/reactivate", f -> ids(f.card()), NONE),
            op("cards: faturas", HttpMethod.GET, CARD + "/invoices", f -> ids(f.card()), NONE),
            op("cards: fatura atual", HttpMethod.GET, CARD + "/invoices/current", f -> ids(f.card()), NONE),
            op("cards: faturas futuras", HttpMethod.GET, CARD + "/invoices/future", f -> ids(f.card()), NONE),
            op("cards: fatura do mês", HttpMethod.GET, CARD_INVOICE, f -> ids(f.card(), "2026-10"), NONE),
            op("cards: lançamentos da fatura", HttpMethod.GET, CARD_INVOICE + "/items", f -> ids(f.card(), "2026-10"),
                    NONE),
            op("cards: utilização", HttpMethod.GET, CARD + "/utilization", f -> ids(f.card()), NONE),
            op("cards: evolução da utilização", HttpMethod.GET, CARD + "/utilization/history", f -> ids(f.card()),
                    NONE),
            op("cards: compra", HttpMethod.POST, CARD + "/purchases", f -> ids(f.card()), idempotent(json(f -> """
                    {"amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "2026-09-22",
                     "description": "Invasora", "installmentCount": 1}
                    """))),
            op("cards: pagamento", HttpMethod.POST, CARD + "/payments", f -> ids(f.card()), idempotent(json(f -> """
                    {"accountId": "%s", "amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "2026-09-22"}
                    """.formatted(f.account())))),
            op("cards: estorno", HttpMethod.POST, CARD + "/credits", f -> ids(f.card()), idempotent(json(f -> """
                    {"amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "2026-09-22",
                     "description": "Invasora"}
                    """))),
            op("cards: conciliar pagamento", HttpMethod.POST, CARD + "/payments/reconcile", f -> ids(f.card()),
                    json(f -> """
                            {"transactionId": "%s"}
                            """.formatted(f.posted()))),
            op("imports: upload de fatura", HttpMethod.POST, IMPORTS, f -> new Object[0],
                    (request, f) -> request.multipart().file(statement()).param("cardId", f.card())
                            .header("Idempotency-Key", newKey())),
            op("cards: parcelamentos", HttpMethod.GET, CARD + "/installments", f -> ids(f.card()), NONE),
            op("cards: parcelamento", HttpMethod.GET, CARD + "/installments/{transactionId}",
                    f -> ids(f.card(), f.purchase()), NONE));

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
            cross("transactions: marcar transação alheia como reembolso", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::posted,
                    (mvc, own, id) -> mvc.post().uri(TRANSACTION + "/mark-as-refund", own.workspace(), id)),
            // ADR-0012 §7: categorizar transação alheia = inexistente.
            cross("transactions: categorizar transação alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::posted, (mvc, own, id) -> mvc.put().uri(TRANSACTION + "/category", own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"categoryCode": "OTHER_EXPENSE"}
                                    """)),
            // ADR-0005 §1 (adendo): filtro por conta alheia = conta inexistente → lista vazia.
            cross("transactions: filtrar por conta alheia", HttpStatus.OK, null, Fixture::account,
                    (mvc, own, id) -> mvc.get().uri(TRANSACTIONS, own.workspace()).param("accountId", id)),

            // ADR-0009 §4: conta de destino ativa e do mesmo Workspace; §17: batch alheio → 404 IMPORT_NOT_FOUND.
            cross("imports: upload na conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(IMPORTS, own.workspace()).multipart().file(statement())
                            .param("accountId", id).header("Idempotency-Key", newKey())),
            // ADR-0009 §19: fatura num cartão alheio = cartão inexistente.
            cross("imports: upload no cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.post().uri(IMPORTS, own.workspace()).multipart().file(statement())
                            .param("cardId", id).header("Idempotency-Key", newKey())),
            cross("imports: consultar importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.get().uri(IMPORT, own.workspace(), id)),
            cross("imports: linhas de importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.get().uri(IMPORT + "/records", own.workspace(), id)),
            cross("imports: confirmar importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.post().uri(IMPORT + "/confirm", own.workspace(), id)),
            cross("imports: cancelar importação alheia", HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", Fixture::importId,
                    (mvc, own, id) -> mvc.post().uri(IMPORT + "/cancel", own.workspace(), id)),

            // ADR-0023 §1: insights relacionados a uma transação alheia = transação inexistente.
            cross("insights: relacionados a transação alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::posted, (mvc, own, id) -> mvc.get().uri(INSIGHTS + "/related", own.workspace())
                            .param("transactionId", id)),

            // ADR-0026 §1: cartão de outro Workspace → 404 CARD_NOT_FOUND; conta padrão alheia → ACCOUNT_NOT_FOUND.
            cross("cards: consultar cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(CARD, own.workspace(), id)),
            cross("cards: editar cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.put().uri(CARD, own.workspace(), id).contentType(MediaType.APPLICATION_JSON)
                            .content(CARD_JSON)),
            cross("cards: arquivar cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.post().uri(CARD + "/archive", own.workspace(), id)),
            cross("cards: reativar cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.post().uri(CARD + "/reactivate", own.workspace(), id)),
            cross("cards: faturas de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(CARD + "/invoices", own.workspace(), id)),
            cross("cards: fatura atual de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(CARD + "/invoices/current", own.workspace(), id)),
            cross("cards: faturas futuras de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(CARD + "/invoices/future", own.workspace(), id)),
            cross("cards: fatura do mês de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(CARD_INVOICE, own.workspace(), id, "2026-10")),
            cross("cards: lançamentos de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(CARD_INVOICE + "/items", own.workspace(), id, "2026-10")),
            cross("cards: utilização de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(CARD + "/utilization", own.workspace(), id)),
            cross("cards: evolução da utilização de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND",
                    Fixture::card, (mvc, own, id) -> mvc.get().uri(CARD + "/utilization/history", own.workspace(), id)),
            cross("cards: conta padrão alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(CARDS, own.workspace()).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nickname": "Com conta alheia", "brand": "VISA", "last4": "1234",
                                     "limit": {"amount": "100.00", "currency": "BRL"}, "closingDay": 28, "dueDay": 5,
                                     "defaultPaymentAccountId": "%s"}
                                    """.formatted(id))),

            // ADR-0026 §10: lançamentos em cartão alheio, com conta alheia ou sobre compra alheia = inexistente.
            cross("cards: compra no cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.post().uri(CARD + "/purchases", own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey()).content("""
                                    {"amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "2026-09-22",
                                     "description": "No cartão alheio"}
                                    """)),
            cross("cards: pagamento de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.post().uri(CARD + "/payments", own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey()).content("""
                                    {"accountId": "%s", "amount": {"amount": "1.00", "currency": "BRL"},
                                     "occurredOn": "2026-09-22"}
                                    """.formatted(own.account()))),
            cross("cards: pagamento com conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(CARD + "/payments", own.workspace(), own.card())
                            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey()).content("""
                                    {"accountId": "%s", "amount": {"amount": "1.00", "currency": "BRL"},
                                     "occurredOn": "2026-09-22"}
                                    """.formatted(id))),
            // ADR-0025 §7 (adendo): conciliar em cartão alheio ou um débito alheio = inexistente.
            cross("cards: conciliar pagamento no cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND",
                    Fixture::card, (mvc, own, id) -> mvc.post().uri(CARD + "/payments/reconcile", own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"transactionId": "%s"}
                                    """.formatted(own.posted()))),
            cross("cards: conciliar débito alheio", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", Fixture::posted,
                    (mvc, own, id) -> mvc.post().uri(CARD + "/payments/reconcile", own.workspace(), own.card())
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"transactionId": "%s"}
                                    """.formatted(id))),
            cross("cards: estorno de compra alheia", HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", Fixture::purchase,
                    (mvc, own, id) -> mvc.post().uri(CARD + "/credits", own.workspace(), own.card())
                            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey()).content("""
                                    {"amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "2026-09-22",
                                     "description": "Estorno alheio", "refundOfTransactionId": "%s"}
                                    """.formatted(id))),
            cross("cards: parcelamento de compra alheia", HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                    Fixture::purchase,
                    (mvc, own, id) -> mvc.get().uri(CARD + "/installments/{transactionId}", own.workspace(),
                            own.card(), id)),
            cross("transactions: filtrar por cartão alheio", HttpStatus.OK, null, Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(TRANSACTIONS, own.workspace()).param("cardId", id)),

            // ADR-0035: objetivo de outro Workspace = inexistente; conta alheia como destino, origem ou origem do
            // aporte = conta inexistente.
            cross("goals: consultar objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.get().uri(GOAL, own.workspace(), id)),
            cross("goals: editar objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.put().uri(GOAL, own.workspace(), id).contentType(MediaType.APPLICATION_JSON)
                            .content(GOAL_UPDATE_JSON)),
            cross("goals: aplicar plano em objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.put().uri(GOAL + "/plan", own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).content(GOAL_PLAN_JSON)),
            cross("goals: pausar objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.post().uri(GOAL + "/pause", own.workspace(), id)),
            cross("goals: retomar objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.post().uri(GOAL + "/resume", own.workspace(), id)),
            cross("goals: concluir objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.post().uri(GOAL + "/complete", own.workspace(), id)),
            cross("goals: arquivar objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.post().uri(GOAL + "/archive", own.workspace(), id)),
            cross("goals: aporte em objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.post().uri(GOAL + "/contributions", own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey())
                            .content(contributionJson(own.account()))),
            cross("goals: histórico de objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.get().uri(GOAL + "/contributions", own.workspace(), id)),
            cross("goals: simular objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.post().uri(GOAL + "/simulations", own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).content(SIMULATION_JSON)),
            cross("goals: projeção de objetivo alheio", HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", Fixture::goal,
                    (mvc, own, id) -> mvc.get().uri(GOAL + "/projection", own.workspace(), id)),
            cross("goals: prévia com destino alheio", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(GOALS + "/simulations/preview", own.workspace())
                            .contentType(MediaType.APPLICATION_JSON).content(previewJson(id))),
            cross("goals: destino em conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(GOALS, own.workspace()).contentType(MediaType.APPLICATION_JSON)
                            .content(goalJson(id, null))),
            cross("goals: origem em conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(GOALS, own.workspace()).contentType(MediaType.APPLICATION_JSON)
                            .content(goalJson(own.account(), id))),
            cross("goals: aporte vindo de conta alheia", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", Fixture::account,
                    (mvc, own, id) -> mvc.post().uri(GOAL + "/contributions", own.workspace(), own.goal())
                            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey())
                            .content(contributionJson(id))),

            // ADR-0032 §1: cartão do filtro de outro Workspace = cartão inexistente.
            cross("forecast: faturas de cartão alheio", HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", Fixture::card,
                    (mvc, own, id) -> mvc.get().uri(FORECAST + "/invoices", own.workspace()).param("cardId", id)),

            // ADR-0032 §2: série de outro Workspace = série inexistente.
            cross("despesas recorrentes: detalhe série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::expenseSeries, (mvc, own, id) -> mvc.get().uri(EXPENSE, own.workspace(), id)),
            cross("despesas recorrentes: confirmar série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::expenseSeries, (mvc, own, id) -> mvc.post().uri(EXPENSE + "/confirm", own.workspace(), id)),
            cross("despesas recorrentes: ignorar série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::expenseSeries, (mvc, own, id) -> mvc.post().uri(EXPENSE + "/ignore", own.workspace(), id)),
            cross("despesas recorrentes: ajustar série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::expenseSeries, (mvc, own, id) -> mvc.put().uri(EXPENSE + "/adjustment", own.workspace(), id).contentType(MediaType.APPLICATION_JSON).content(ADJUSTMENT_JSON)),
            cross("despesas recorrentes: remover ajuste de série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::expenseSeries, (mvc, own, id) -> mvc.delete().uri(EXPENSE + "/adjustment", own.workspace(), id)),
            cross("receitas recorrentes: detalhe série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::incomeSeries, (mvc, own, id) -> mvc.get().uri(INCOME, own.workspace(), id)),
            cross("receitas recorrentes: confirmar série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::incomeSeries, (mvc, own, id) -> mvc.post().uri(INCOME + "/confirm", own.workspace(), id)),
            cross("receitas recorrentes: ignorar série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::incomeSeries, (mvc, own, id) -> mvc.post().uri(INCOME + "/ignore", own.workspace(), id)),
            cross("receitas recorrentes: ajustar série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::incomeSeries, (mvc, own, id) -> mvc.put().uri(INCOME + "/adjustment", own.workspace(), id).contentType(MediaType.APPLICATION_JSON).content(ADJUSTMENT_JSON)),
            cross("receitas recorrentes: remover ajuste de série alheia", HttpStatus.NOT_FOUND, "RECURRING_SERIES_NOT_FOUND",
                    Fixture::incomeSeries, (mvc, own, id) -> mvc.delete().uri(INCOME + "/adjustment", own.workspace(), id)),

            // ADR-0040 §2: conversa ou pergunta de outro Workspace (ou de outra pessoa) = inexistente.
            cross("copilot: consultar conversa alheia", HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND",
                    Fixture::conversation, (mvc, own, id) -> mvc.get().uri(COPILOT_CONVERSATION, own.workspace(), id)),
            cross("copilot: apagar conversa alheia", HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND",
                    Fixture::conversation,
                    (mvc, own, id) -> mvc.delete().uri(COPILOT_CONVERSATION, own.workspace(), id)),
            cross("copilot: perguntar numa conversa alheia", HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND",
                    Fixture::conversation, (mvc, own, id) -> mvc.post().uri(COPILOT_QUESTIONS, own.workspace())
                            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey()).content("""
                                    {"question": "E agora?", "conversationId": "%s"}
                                    """.formatted(id))),
            cross("copilot: feedback de pergunta alheia", HttpStatus.NOT_FOUND, "COPILOT_REQUEST_NOT_FOUND",
                    Fixture::copilotRequest, (mvc, own, id) -> mvc.put().uri(COPILOT_FEEDBACK, own.workspace(), id)
                            .contentType(MediaType.APPLICATION_JSON).content(COPILOT_FEEDBACK_JSON)));

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
    // 3. Leituras derivadas dos cartões só com o próprio Workspace (ADR-0006 §18, ADR-0027, ADR-0028)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void overviewInvoicesToPayOnlyShowTheOwnCards() throws Exception {
        MvcTestResult overview = mvc.get().uri(WORKSPACE_SCOPE + "/overview", ana.workspace()).with(ana()).exchange();

        assertThat(overview).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.cards.activeCards", n -> assertThat(n).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.cards.upcomingEvents[*].card.id",
                        ids -> assertThat(ids).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                                .allMatch(ana.card()::equals));
        assertNoLeak(overview, bob, "Conta do Bob Cartão", "Compra do Bob");
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
        MvcTestResult card = mvc.post().uri(CARDS, workspace).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nickname": "%s Cartão", "brand": "MASTERCARD", "last4": "4821",
                         "limit": {"amount": "%s", "currency": "BRL"}, "closingDay": 28, "dueDay": 5,
                         "defaultPaymentAccountId": "%s"}
                        """.formatted(accountName, amount, account)).exchange();
        assertThat(card).hasStatus(HttpStatus.CREATED);
        MvcTestResult purchase = mvc.post().uri(CARD + "/purchases", workspace, read(card, "$.id")).with(who)
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", newKey()).content("""
                        {"amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "2026-09-22",
                         "description": "%s", "installmentCount": 3}
                        """.formatted(amount, description)).exchange();
        assertThat(purchase).hasStatus(HttpStatus.CREATED);
        // Consentimentos ativos: uma revogação vinda de fora mudaria a tabela (ADR-0038).
        for (String consent : new String[] {"COPILOT_DATA_USE", "COPILOT_HISTORY"}) {
            assertThat(mvc.put().uri(CONSENTS + "/{purpose}", workspace, consent).with(who)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"granted": true, "termsVersion": "%s"}
                            """.formatted(consent.equals("COPILOT_DATA_USE") ? "copilot-data-v1" : "copilot-history-v1"))
                    .exchange()).hasStatus(HttpStatus.OK);
        }
        String[] copilot = seedCopilot(workspace, description);
        return new Fixture(workspace, account, other, archived, posted, pending, read(upload, "$.id"),
                read(card, "$.id"), read(purchase, "$.transaction.id"),
                seedSeries(workspace, "EXPENSE", description + " (despesa)"),
                seedSeries(workspace, "INCOME", description + " (receita)"),
                seedGoal(who, workspace, other, account, description), copilot[0], copilot[1]);
    }

    /**
     * Uma conversa guardada (com uma mensagem), uma pergunta no usage ledger e um feedback do dono do Workspace;
     * devolve o id da conversa e o da pergunta (ADR-0040).
     */
    private String[] seedCopilot(String workspace, String description) {
        WorkspaceId workspaceId = new WorkspaceId(UUID.fromString(workspace));
        UserId owner = new UserId(jdbc.queryForObject("select user_id from workspace_memberships where workspace_id = ?",
                UUID.class, workspaceId.value()));
        Instant now = Instant.now();
        UUID requestId = UUID.randomUUID();
        aiUsage.append(new AiUsage(UUID.randomUUID(), workspaceId, owner, requestId, AiRequestType.INTENT, "simulated",
                "simulated-v1", new TokenUsage(10, 5, 0, 0), BigDecimal.ZERO, "pricing-test", "intent-v2@test", "", 1,
                AiOutcome.OK, now));
        UUID conversation = conversations.createConversation(workspaceId, owner, description + " pergunta", now);
        conversations.addMessage(workspaceId, owner, conversation, new NewMessage(UuidV7.generate(now),
                description + " pergunta", new CopilotResult(requestId, ResultCode.OK, Intent.OVERVIEW,
                        RenderedAnswer.plain(AnswerType.ANALYSIS, description + " resposta", List.of()), null,
                        ValidationStatus.VALID, null, null, Map.of()), now, now.plus(Duration.ofDays(90))));
        copilotFeedback.upsert(new FeedbackStore.Feedback(requestId, workspaceId, owner, FeedbackRating.HELPFUL, null,
                Intent.OVERVIEW, Map.of(), now, now.plus(Duration.ofDays(90))));
        return new String[] {conversation.toString(), requestId.toString()};
    }

    /** Objetivo com a segunda conta como destino dedicado (ADR-0035). */
    private String seedGoal(RequestPostProcessor who, String workspace, String destination, String source,
                            String description) throws Exception {
        MvcTestResult goal = mvc.post().uri(GOALS, workspace).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(goalJson(destination, source).replace("\"Objetivo\"", "\"" + description + " objetivo\""))
                .exchange();
        assertThat(goal).hasStatus(HttpStatus.CREATED);
        return read(goal, "$.id");
    }

    private static String goalJson(String destination, String source) {
        return """
                {"name": "Objetivo", "type": "PURCHASE", "targetAmount": {"amount": "1000.00", "currency": "BRL"},
                 "plannedMonthly": {"amount": "100.00", "currency": "BRL"}, "destinationAccountId": "%s",
                 "sourceAccountId": %s}
                """.formatted(destination, source == null ? "null" : "\"" + source + "\"");
    }

    private static String previewJson(String destination) {
        return """
                {"type": "PURCHASE", "targetAmount": {"amount": "1000.00", "currency": "BRL"},
                 "plannedMonthly": {"amount": "100.00", "currency": "BRL"}, "destinationAccountId": "%s"}
                """.formatted(destination);
    }

    /** Aporte de hoje (data de negócio): o objetivo do seed começa hoje. */
    private static String contributionJson(String source) {
        return """
                {"sourceAccountId": "%s", "amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "%s"}
                """.formatted(source, java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")));
    }

    /** Série recorrente gravada direto, como o job do Forecast faria; devolve o id opaco da API. */
    private String seedSeries(String workspace, String direction, String label) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into forecast_recurring_series (workspace_id, direction, series_key, label, channel_kind,
                  channel_id, periodicity, expected_day, expected_amount, amount_min, amount_max, currency,
                  confidence, occurrences, algorithm_version, status, origin, created_at, updated_at, id)
                values (?::uuid, ?, ?, ?, 'ACCOUNT', ?::uuid, 'MONTHLY', 5, 100, 90, 110, 'BRL', 'HIGH', '{}',
                  'forecast-v1', 'DETECTED', 'DETECTED', now(), now(), ?::uuid)
                """, workspace, direction, "merchant:" + id, label, UUID.randomUUID().toString(), id);
        return id;
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
                        "outbox_events", "processed_events", "health_workspaces", "health_snapshots", "forecast_workspaces",
                        "forecast_snapshots", "forecast_recurring_series", "forecast_month_outcomes",
                        "insights_workspaces", "insights", "credit_cards", "card_invoices", "card_installments",
                        "goals", "consents", "consent_audit", "ai_usage", "copilot_conversations", "copilot_messages",
                        "copilot_feedback")
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
