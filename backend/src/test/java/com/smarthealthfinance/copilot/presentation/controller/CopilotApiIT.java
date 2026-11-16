package com.smarthealthfinance.copilot.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.port.CopilotIdempotency;
import com.smarthealthfinance.copilot.application.service.SubmitCopilotQuestion;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.time.Duration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.awaitility.Awaitility.await;

/**
 * API de perguntas e uso do Copilot (ADR-0040 §2–§5, §8) de ponta a ponta com Postgres, Redis, casos de uso reais e o
 * provedor simulado: o contrato do {@code CopilotAnswer}, a ordem das verificações, a idempotência, o contexto de
 * entrada e o isolamento entre Workspaces. A tradução de cada erro para HTTP, inclusive 503, fica em
 * {@code CopilotExceptionHandlerTest}.
 */
class CopilotApiIT extends IntegrationTest {

    private static final String WS = "/api/v1/workspaces/{id}";
    private static final String QUESTIONS = WS + "/copilot/questions";
    private static final String USAGE = WS + "/copilot/usage";
    private static final String GOAL_NAME = "Viagem Sigilosa para Lisboa";
    private static final String BALANCE = "{\"question\": \"Qual é o meu saldo?\"}";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    AiUsageLedger ledger;

    @Autowired
    CopilotIdempotency idempotency;

    private String anasWorkspace;
    private String bobsWorkspace;
    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = provision("sub-ana", "ana@example.com");
        bobsWorkspace = provision("sub-bob", "bob@example.com");
    }

    // --- 200 e contrato ---

    @Test
    void answersWithTheFullContractAndKeepsNoContentInRedis() throws Exception {
        seedAnasMoney();
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        String key = key();

        MvcTestResult result = ask(anasWorkspace, key, BALANCE);

        assertThat(result).hasStatus(HttpStatus.OK);
        String body = body(result);
        assertThat(JsonPath.<String>read(body, "$.requestId")).isNotBlank();
        assertThat(JsonPath.<Object>read(body, "$.conversationId")).isNull();
        assertThat(JsonPath.<Object>read(body, "$.messageId")).isNull();
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("OK");
        assertThat(JsonPath.<String>read(body, "$.intent")).isEqualTo("OVERVIEW");
        assertThat(JsonPath.<String>read(body, "$.question")).isEqualTo("Qual é o meu saldo?");
        assertThat(JsonPath.<String>read(body, "$.answer.type")).isEqualTo("analysis");
        assertThat(JsonPath.<String>read(body, "$.validation")).isEqualTo("VALID");
        assertThat(JsonPath.<String>read(body, "$.confidence")).isIn("high", "medium", "low");
        assertThat(JsonPath.<String>read(body, "$.versions.answerPrompt")).matches("answer-v2@[0-9a-f]{8}");
        assertThat(JsonPath.<String>read(body, "$.versions.intentPrompt")).matches("intent-v2@[0-9a-f]{8}");
        assertThat(JsonPath.<String>read(body, "$.createdAt")).isNotBlank();
        assertThat(JsonPath.<List<String>>read(body, "$.notices")).isNotNull();
        assertThat(JsonPath.<Object>read(body, "$.answer.breakdown")).isNull();
        assertThat(JsonPath.<List<Object>>read(body, "$.answer.actions")).isNotNull();
        assertThat(JsonPath.<String>read(body, "$.answer.dataUsed[0].label")).isNotBlank();
        assertThat(JsonPath.<Boolean>read(body, "$.answer.dataUsed[0].partial")).isNotNull();
        assertThat(body).doesNotContain("persisted");

        List<String> valueRefs = JsonPath.read(body, "$.answer.values[*].ref");
        List<String> segmentRefs = JsonPath.read(body, "$.answer.segments[?(@.kind=='VALUE')].ref");
        assertThat(segmentRefs).isNotEmpty().isSubsetOf(valueRefs);
        assertThat(JsonPath.<List<String>>read(body, "$.answer.segments[?(@.kind=='TEXT')].text")).isNotEmpty();
        assertThat(JsonPath.<String>read(body, "$.answer.values[0].formatted")).contains("R$");

        Set<String> keys = redis.keys("shf:copilot:idem:" + anasWorkspace + ":*");
        assertThat(keys).hasSize(1);
        String stored = redis.opsForValue().get(keys.iterator().next());
        assertThat(stored).startsWith("DONE|").doesNotContain("saldo", "R$", key);
        assertThat(keys.iterator().next()).doesNotContain(key);
        assertThat(redis.getExpire(keys.iterator().next())).isBetween(1L, 600L);
    }

    @Test
    void invoiceContextForcesTheCardAndTheMonthAndGoalContextCarriesTheScreenLinkForActions() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        String card = createCard();
        String dueMonth = JsonPath.read(get("/api/v1/workspaces/{id}/cards/" + card + "/invoices/current",
                anasWorkspace), "$.invoice.dueMonth");
        String goal = createGoal();

        MvcTestResult invoice = ask(anasWorkspace, key(), """
                {"question": "Por que minha fatura está mais alta?",
                 "context": {"kind": "INVOICE", "cardId": "%s", "month": "%s"}}""".formatted(card, dueMonth));
        assertThat(invoice).hasStatus(HttpStatus.OK);
        String invoiceBody = body(invoice);
        assertThat(JsonPath.<String>read(invoiceBody, "$.intent")).isEqualTo("CARDS");
        assertThat(JsonPath.<String>read(invoiceBody, "$.answer.dataUsed[0].label")).startsWith("Fatura");
        assertThat(JsonPath.<List<String>>read(invoiceBody, "$.answer.actions[?(@.kind=='OPEN_INVOICE')].params.month"))
                .allMatch(dueMonth::equals);

        MvcTestResult action = ask(anasWorkspace, key(), """
                {"question": "Pague este objetivo para mim",
                 "context": {"kind": "GOAL", "goalId": "%s"}}""".formatted(goal));
        assertThat(action).hasStatus(HttpStatus.OK);
        String actionBody = body(action);
        assertThat(JsonPath.<String>read(actionBody, "$.code")).isEqualTo("OUT_OF_SCOPE");
        assertThat(JsonPath.<String>read(actionBody, "$.intent")).isEqualTo("UNSUPPORTED_ACTION");
        assertThat(JsonPath.<String>read(actionBody, "$.answer.actions[0].kind")).isEqualTo("OPEN_GOAL");
        assertThat(JsonPath.<String>read(actionBody, "$.answer.actions[0].label")).isEqualTo("Ver objetivo");
        assertThat(JsonPath.<String>read(actionBody, "$.answer.actions[0].targetId")).isEqualTo(goal);

        MvcTestResult withoutContext = ask(anasWorkspace, key(), "{\"question\": \"Pague este objetivo para mim\"}");
        assertThat(JsonPath.<List<Object>>read(body(withoutContext), "$.answer.actions")).isEmpty();
    }

    // --- 400 / 403 / 404 ---

    @Test
    void validationErrorsAreBadRequestAfterTheAdmission() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        String tooLong = "x".repeat(501);

        assertError(ask(anasWorkspace, null, BALANCE), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, "chave inválida!", BALANCE), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), "{\"question\": \"   \"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), "{\"question\": \"" + tooLong + "\"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), """
                {"question": "E agora?", "previousQuestions": ["a", "b", "c", "d"]}"""), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), """
                {"question": "E agora?", "conversationId": "%s", "previousQuestions": ["a"]}"""
                .formatted(UUID.randomUUID())), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), """
                {"question": "Oi", "context": {"kind": "PLANET", "goalId": "%s"}}""".formatted(UUID.randomUUID())),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), """
                {"question": "Oi", "context": {"kind": "INVOICE", "cardId": "%s", "month": "2026-13"}}"""
                .formatted(UUID.randomUUID())), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), """
                {"question": "Oi", "context": {"kind": "GOAL", "goalId": "não-é-uuid"}}"""),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from ai_usage where request_type <> 'INTENT'",
                Long.class)).isZero();
    }

    @Test
    void aPreviousQuestionOverTheLimitIsBadRequestAndThePreviousQuestionsReachTheModelWhenValid() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);

        assertError(ask(anasWorkspace, key(), "{\"question\": \"E agora?\", \"previousQuestions\": [\""
                + "x".repeat(501) + "\"]}"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        MvcTestResult valid = ask(anasWorkspace, key(), """
                {"question": "E o saldo?", "previousQuestions": ["Quanto gastei?", "E em agosto?"]}""");
        assertThat(valid).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<String>read(body(valid), "$.question")).isEqualTo("E o saldo?");
    }

    @Test
    void sizeLimitsAreRejectedBeforeTheKeyIsReservedAndTheBodyIsCappedAt16Kb() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);

        assertError(ask(anasWorkspace, key(), "{\"question\": \"" + "x".repeat(501) + "\"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(ask(anasWorkspace, key(), "{\"question\": \"Oi\", \"previousQuestions\": [\"a\", \"b\", \"c\", "
                + "\"d\"]}"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(redis.keys("shf:copilot:idem:" + anasWorkspace + ":*")).as("nenhuma chave reservada").isEmpty();

        String huge = "{\"question\": \"Oi\", \"previousQuestions\": [\"" + "x".repeat(20 * 1024) + "\"]}";
        MvcTestResult tooBig = ask(anasWorkspace, key(), huge);
        assertError(tooBig, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(JsonPath.<String>read(body(tooBig), "$.details[0].code")).isEqualTo("TOO_LARGE");
        assertThat(body(tooBig)).as("o corpo nunca é ecoado").doesNotContain("xxxx");
        assertThat(redis.keys("shf:copilot:idem:" + anasWorkspace + ":*")).isEmpty();

        assertError(askAs(bob(), anasWorkspace, key(), "{\"question\": \"" + "x".repeat(501) + "\"}"),
                HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND");
    }

    @Test
    void theBodyLimitIsExactlySixteenKibibytes() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        String json = "{\"question\": \"Oi\"}";

        String atLimit = json + " ".repeat(16 * 1024 - json.length());
        assertThat(atLimit.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(16384);
        MvcTestResult accepted = ask(anasWorkspace, key(), atLimit);
        if (accepted.getResponse().getStatus() == HttpStatus.BAD_REQUEST.value()) {
            assertThat(JsonPath.<String>read(body(accepted), "$.details[0].code")).isNotEqualTo("TOO_LARGE");
        }

        MvcTestResult over = ask(anasWorkspace, key(), atLimit + " ");
        assertError(over, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(JsonPath.<String>read(body(over), "$.details[0].code")).isEqualTo("TOO_LARGE");
    }

    @Test
    void withoutConsentIsForbiddenAndTheKeyIsReleased() throws Exception {
        String key = key();

        assertError(ask(anasWorkspace, key, BALANCE), HttpStatus.FORBIDDEN, "COPILOT_CONSENT_REQUIRED");

        assertThat(redis.keys("shf:copilot:idem:" + anasWorkspace + ":*")).isEmpty();
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        assertThat(ask(anasWorkspace, key, BALANCE)).hasStatus(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select count(*) from ai_usage", Long.class))
                .as("sem dados no Workspace só a intenção foi ao modelo").isEqualTo(1);
    }

    @Test
    void anUnknownConversationIsNotFoundBecauseThereIsNoHistoryYet() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);

        assertError(ask(anasWorkspace, key(), """
                {"question": "E agora?", "conversationId": "%s"}""".formatted(UUID.randomUUID())),
                HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
    }

    @Test
    void contextOfAnotherWorkspaceOrMissingIsNotFoundAndNeverSpendsTheBucketOrTheQuota() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        grantConsent("sub-bob", "bob@example.com", bobsWorkspace);
        String anasGoal = createGoal();

        // Mais tentativas inválidas do que a capacidade do balde (3): nenhuma gasta token nem quota.
        for (int i = 0; i < 4; i++) {
            assertError(askAs(bob(), bobsWorkspace, key(), """
                    {"question": "Como está meu objetivo?", "context": {"kind": "GOAL", "goalId": "%s"}}"""
                    .formatted(anasGoal)), HttpStatus.NOT_FOUND, "COPILOT_CONTEXT_NOT_FOUND");
        }
        assertError(ask(anasWorkspace, key(), """
                {"question": "Oi", "context": {"kind": "INSIGHT", "insightId": "%s"}}"""
                .formatted(UUID.randomUUID())), HttpStatus.NOT_FOUND, "COPILOT_CONTEXT_NOT_FOUND");
        assertError(ask(anasWorkspace, key(), """
                {"question": "Oi", "context": {"kind": "INSTALLMENT_PLAN", "cardId": "%s", "planId": "%s"}}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID())), HttpStatus.NOT_FOUND,
                "COPILOT_CONTEXT_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from ai_usage", Long.class)).isZero();

        for (int i = 0; i < 3; i++) {
            assertThat(askAs(bob(), bobsWorkspace, key(), BALANCE)).hasStatus(HttpStatus.OK);
        }
    }

    @Test
    void aNonMemberIsNotFoundAndTheRedisIsNeverTouched() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);

        assertError(askAs(bob(), anasWorkspace, key(), BALANCE), HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND");
        assertError(askAs(bob(), anasWorkspace, null, BALANCE), HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND");
        assertThat(mvc.get().uri(USAGE, anasWorkspace).with(bob())).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(redis.keys("shf:copilot:idem:" + anasWorkspace + ":*")).isEmpty();
        assertThat(redis.keys("shf:copilot:rl:" + anasWorkspace + ":*")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from ai_usage", Long.class)).isZero();
    }

    // --- 429 ---

    @Test
    void rateLimitIs429WithRetryAfterInSecondsAndTheKeyIsReleased() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        for (int i = 0; i < 3; i++) {
            assertThat(ask(anasWorkspace, key(), BALANCE)).hasStatus(HttpStatus.OK);
        }
        String key = key();

        MvcTestResult limited = ask(anasWorkspace, key, BALANCE);

        assertError(limited, HttpStatus.TOO_MANY_REQUESTS, "COPILOT_RATE_LIMITED");
        String retryAfter = limited.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).matches("\\d+");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 20L);
        assertThat(redis.keys("shf:copilot:idem:" + anasWorkspace + ":*")).hasSize(3);
    }

    @Test
    void quotaIs429WithResetsAtInTheBodyAndTheKeyIsReleased() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        WorkspaceId workspace = new WorkspaceId(UUID.fromString(anasWorkspace));
        UserId ana = new UserId(jdbc.queryForObject("select id from users where email = 'ana@example.com'",
                UUID.class));
        for (int i = 0; i < 10; i++) {
            ledger.append(new AiUsage(UUID.randomUUID(), workspace, ana, UUID.randomUUID(), AiRequestType.INTENT,
                    "simulated", "simulated-v1", new TokenUsage(10, 0, 0, 0), BigDecimal.ZERO, "pricing-test", "",
                    "", 1, AiOutcome.OK, Instant.now()));
        }

        MvcTestResult exceeded = ask(anasWorkspace, key(), BALANCE);

        assertError(exceeded, HttpStatus.TOO_MANY_REQUESTS, "COPILOT_QUOTA_EXCEEDED");
        assertThat(exceeded.getResponse().getHeader("Retry-After")).isNull();
        assertThat(Instant.parse(JsonPath.read(body(exceeded), "$.resetsAt"))).isAfter(Instant.now());
        assertThat(redis.keys("shf:copilot:idem:" + anasWorkspace + ":*")).isEmpty();
    }

    // --- idempotência ---

    @Test
    void replaysNeverSpendAndAnswerInProgressDifferentBodyAndAlreadyAnswered() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        String key = key();
        assertThat(ask(anasWorkspace, key, BALANCE)).hasStatus(HttpStatus.OK);
        long calls = jdbc.queryForObject("select count(*) from ai_usage", Long.class);

        // Concluído, mesmo corpo.
        assertError(ask(anasWorkspace, key, BALANCE), HttpStatus.CONFLICT, "COPILOT_ALREADY_ANSWERED");
        // Outro corpo com a mesma chave.
        assertError(ask(anasWorkspace, key, "{\"question\": \"Outra pergunta\"}"), HttpStatus.UNPROCESSABLE_CONTENT,
                "IDEMPOTENCY_KEY_REUSED");
        assertError(ask(anasWorkspace, key, """
                {"question": "Qual é o meu saldo?", "previousQuestions": ["Antes"]}"""),
                HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED");

        // Em andamento: outra requisição ainda não terminou (reserva feita, sem conclusão).
        String inFlight = key();
        UUID workspace = UUID.fromString(anasWorkspace);
        UserId ana = new UserId(jdbc.queryForObject("select id from users where email = 'ana@example.com'",
                UUID.class));
        String hash = SubmitCopilotQuestion.fingerprint(AskCommand.of("Qual é o meu saldo?"), null);
        assertThat(idempotency.reserve(new WorkspaceId(workspace), ana, inFlight, hash, UUID.randomUUID()))
                .isInstanceOf(CopilotIdempotency.Reservation.Reserved.class);
        assertError(ask(anasWorkspace, inFlight, BALANCE), HttpStatus.CONFLICT, "COPILOT_REQUEST_IN_PROGRESS");

        assertThat(jdbc.queryForObject("select count(*) from ai_usage", Long.class))
                .as("replay nunca chama o modelo nem gasta quota").isEqualTo(calls);
    }

    // --- uso ---

    @Test
    void usageNeedsOnlyMembershipAndCountsLikeTheQuota() throws Exception {
        MvcTestResult empty = mvc.get().uri(USAGE, anasWorkspace).with(ana()).exchange();

        assertThat(empty).hasStatus(HttpStatus.OK);
        String emptyBody = body(empty);
        assertThat(JsonPath.<Integer>read(emptyBody, "$.month.used")).isZero();
        assertThat(JsonPath.<Integer>read(emptyBody, "$.month.limit")).isEqualTo(100);
        assertThat(JsonPath.<Integer>read(emptyBody, "$.day.used")).isZero();
        assertThat(JsonPath.<Integer>read(emptyBody, "$.day.limit")).isEqualTo(10);
        assertThat(JsonPath.<String>read(emptyBody, "$.day.resetsAt")).isNotBlank();
        assertThat(JsonPath.<String>read(emptyBody, "$.month.resetsAt")).isNotBlank();
        assertThat(JsonPath.<Object>read(emptyBody, "$.dataScope")).isNull();
        assertThat(JsonPath.<Boolean>read(emptyBody, "$.provisional")).isTrue();

        seedAnasMoney();
        grantConsent("sub-ana", "ana@example.com", anasWorkspace);
        assertThat(ask(anasWorkspace, key(), BALANCE)).hasStatus(HttpStatus.OK);

        // O recorte vem da projeção do Analytics, atualizada de forma assíncrona pelo evento das transações.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(JsonPath.<Object>read(
                body(mvc.get().uri(USAGE, anasWorkspace).with(ana()).exchange()), "$.dataScope")).isNotNull());
        String used = body(mvc.get().uri(USAGE, anasWorkspace).with(ana()).exchange());
        assertThat(JsonPath.<Integer>read(used, "$.month.used")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(used, "$.day.used")).isEqualTo(1);
        String thisMonth = YearMonth.now(ZoneId.of("America/Sao_Paulo")).toString();
        assertThat(JsonPath.<String>read(used, "$.dataScope.from")).isEqualTo(thisMonth);
        assertThat(JsonPath.<String>read(used, "$.dataScope.to")).isEqualTo(thisMonth);

        assertThat(body(mvc.get().uri(USAGE, bobsWorkspace).with(bob()).exchange()))
                .as("o uso é do Workspace de quem pergunta").contains("\"used\":0");
    }

    // --- apoio ---

    private MvcTestResult ask(String workspace, String key, String body) {
        return askAs(ana(), workspace, key, body);
    }

    private MvcTestResult askAs(RequestPostProcessor who, String workspace, String key, String body) {
        var request = mvc.post().uri(QUESTIONS, workspace).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.exchange();
    }

    private void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(body(result), "$.code")).isEqualTo(code);
    }

    private String createCard() throws Exception {
        String account = JsonPath.read(body(mvc.post().uri(WS + "/accounts", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Conta Aurora\", \"type\": \"CHECKING\"}")
                .exchange()), "$.id");
        String card = JsonPath.read(body(mvc.post().uri(WS + "/cards", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"nickname": "Aurora Platinum", "brand": "MASTERCARD", "last4": "4821",
                         "limit": {"amount": "12000.00", "currency": "BRL"}, "closingDay": 25, "dueDay": 5,
                         "defaultPaymentAccountId": "%s"}""".formatted(account)).exchange()), "$.id");
        assertThat(mvc.post().uri(WS + "/cards/" + card + "/purchases", anasWorkspace).with(ana())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"amount": {"amount": "300.00", "currency": "BRL"}, "occurredOn": "%s",
                         "description": "Compra secreta", "installmentCount": 3}""".formatted(today())))
                .hasStatus(HttpStatus.CREATED);
        return card;
    }

    private String createGoal() throws Exception {
        String destination = JsonPath.read(body(mvc.post().uri(WS + "/accounts", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Caixinha Lisboa\", \"type\": \"PAYMENT\"}")
                .exchange()), "$.id");
        MvcTestResult goal = mvc.post().uri(WS + "/goals", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "type": "PURCHASE", "targetAmount": {"amount": "12000.00", "currency": "BRL"},
                         "plannedMonthly": {"amount": "700.00", "currency": "BRL"}, "destinationAccountId": "%s",
                         "sourceAccountId": null}""".formatted(GOAL_NAME, destination)).exchange();
        assertThat(goal).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(goal), "$.id");
    }

    private void seedAnasMoney() throws Exception {
        accountId = JsonPath.read(body(mvc.post().uri(WS + "/accounts", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Conta Principal\", \"type\": \"CHECKING\"}")
                .exchange()), "$.id");
        transaction("INCOME", "5000.00", "SALARIO");
        transaction("EXPENSE", "300.00", "MERCADO DA ANA");
    }

    private void transaction(String type, String amount, String description) {
        assertThat(mvc.post().uri(WS + "/transactions", anasWorkspace).with(ana()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "%s", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "%s", "description": "%s"}""".formatted(type, accountId, amount, today(),
                        description))).hasStatus(HttpStatus.CREATED);
    }

    private void grantConsent(String subject, String email, String workspace) {
        assertThat(mvc.put().uri(WS + "/consents/COPILOT_DATA_USE", workspace).with(request(subject, email))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"granted\": true, \"termsVersion\": \"copilot-data-v1\"}")).hasStatus(HttpStatus.OK);
    }

    private String get(String uri, String workspace) {
        return body(mvc.get().uri(uri, workspace).with(ana()).exchange());
    }

    private String provision(String subject, String email) throws Exception {
        return JsonPath.read(body(mvc.post().uri("/api/v1/users/me").with(request(subject, email)).exchange()),
                "$.workspaceId");
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    private static String key() {
        return "web:" + UUID.randomUUID();
    }

    private static RequestPostProcessor ana() {
        return request("sub-ana", "ana@example.com");
    }

    private static RequestPostProcessor bob() {
        return request("sub-bob", "bob@example.com");
    }

    private static RequestPostProcessor request(String subject, String email) {
        return jwt().jwt(t -> t.subject(subject).claim("iss", ISSUER).claim("email", email).claim("name", "Nome"));
    }
}
