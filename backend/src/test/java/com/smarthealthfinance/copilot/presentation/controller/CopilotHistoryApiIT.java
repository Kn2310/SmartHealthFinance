package com.smarthealthfinance.copilot.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.NewMessage;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.port.FeedbackStore;
import com.smarthealthfinance.copilot.application.usecase.DeleteConversationsOnRevocation;
import com.smarthealthfinance.copilot.application.usecase.PurgeExpiredCopilotData;
import com.smarthealthfinance.copilot.domain.enums.AnswerConfidence;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.FeedbackRating;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.UuidV7;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Histórico, exclusão, feedback, revogação e retenção do Copilot (ADR-0040 §2, §5–§8) de ponta a ponta com Postgres,
 * Redis, RabbitMQ e o provedor simulado. Cada teste faz no máximo 3 perguntas por pessoa (o balde do rate limit); o
 * volume (paginação, expiração) entra direto pela porta de persistência.
 */
class CopilotHistoryApiIT extends IntegrationTest {

    private static final String WS = "/api/v1/workspaces/{id}";
    private static final String QUESTIONS = WS + "/copilot/questions";
    private static final String CONVERSATIONS = WS + "/copilot/conversations";
    private static final String CONVERSATION = CONVERSATIONS + "/{conversationId}";
    private static final String FEEDBACK = QUESTIONS + "/{requestId}/feedback";
    private static final String BALANCE = "{\"question\": \"Qual é o meu saldo?\"}";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ConversationStore store;

    @Autowired
    FeedbackStore feedbackStore;

    @Autowired
    DeleteConversationsOnRevocation revocation;

    @Autowired
    PurgeExpiredCopilotData purge;

    private String anasWorkspace;
    private String bobsWorkspace;
    private WorkspaceId anasWs;
    private UserId anaUser;
    private UserId bobUser;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = provision("sub-ana", "ana@example.com");
        bobsWorkspace = provision("sub-bob", "bob@example.com");
        anasWs = new WorkspaceId(UUID.fromString(anasWorkspace));
        anaUser = user("ana@example.com");
        bobUser = user("bob@example.com");
    }

    // --- gravação e leitura ---

    @Test
    void withHistoryTheAnswerIsStoredListedAndReadBackWithTheSameContract() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        String key = key();
        String longQuestion = "Explique em detalhes por que o meu gasto total do mês passado foi maior do que a "
                + "média dos meses anteriores?";

        MvcTestResult asked = ask(anasWorkspace, key, "{\"question\": \"" + longQuestion + "\"}");

        assertThat(asked).hasStatus(HttpStatus.OK);
        String body = body(asked);
        String conversationId = JsonPath.read(body, "$.conversationId");
        String messageId = JsonPath.read(body, "$.messageId");
        String requestId = JsonPath.read(body, "$.requestId");
        assertThat(conversationId).isNotBlank();
        assertThat(messageId).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from copilot_messages where request_id = ?::uuid", Long.class,
                requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select expires_at - created_at = interval '90 days' from copilot_messages where id = ?::uuid
                """, Boolean.class, messageId)).isTrue();

        MvcTestResult list = mvc.get().uri(CONVERSATIONS, anasWorkspace).with(ana()).exchange();
        assertThat(list).hasStatus(HttpStatus.OK);
        String page = body(list);
        assertThat(JsonPath.<Boolean>read(page, "$.historyEnabled")).isTrue();
        assertThat(JsonPath.<List<Object>>read(page, "$.items")).hasSize(1);
        assertThat(JsonPath.<String>read(page, "$.items[0].id")).isEqualTo(conversationId);
        assertThat(JsonPath.<Integer>read(page, "$.items[0].messageCount")).isEqualTo(1);
        assertThat(JsonPath.<String>read(page, "$.items[0].title")).endsWith("…").hasSizeLessThanOrEqualTo(81)
                .startsWith("Explique em detalhes por que o meu gasto total do mês passado")
                .doesNotContain("média dos meses anteriores?");
        assertThat(JsonPath.<Object>read(page, "$.nextCursor")).isNull();

        MvcTestResult detail = mvc.get().uri(CONVERSATION, anasWorkspace, conversationId).with(ana()).exchange();
        assertThat(detail).hasStatus(HttpStatus.OK);
        String conversation = body(detail);
        assertThat(JsonPath.<String>read(conversation, "$.id")).isEqualTo(conversationId);
        assertThat(JsonPath.<List<Object>>read(conversation, "$.messages")).hasSize(1);
        assertThat(JsonPath.<String>read(conversation, "$.messages[0].id")).isEqualTo(messageId);
        assertThat(JsonPath.<String>read(conversation, "$.messages[0].requestId")).isEqualTo(requestId);
        assertThat(JsonPath.<String>read(conversation, "$.messages[0].question")).isEqualTo(longQuestion);
        assertThat(JsonPath.<String>read(conversation, "$.messages[0].createdAt")).isNotBlank();
        assertThat(JsonPath.<String>read(conversation, "$.messages[0].answer.code"))
                .isEqualTo(JsonPath.<String>read(body, "$.code")).isIn("OK", "SAFE_MODE", "INSUFFICIENT_DATA",
                        "OUT_OF_SCOPE");
        assertThat(JsonPath.<String>read(conversation, "$.messages[0].answer.conversationId"))
                .isEqualTo(conversationId);
        assertThat(JsonPath.<Object>read(conversation, "$.messages[0].answer.answer"))
                .as("a resposta guardada é a renderizada, igual à que saiu no POST")
                .isEqualTo(JsonPath.<Object>read(body, "$.answer"));
        assertThat(JsonPath.<Object>read(conversation, "$.messages[0].answer.versions"))
                .isEqualTo(JsonPath.<Object>read(body, "$.versions"));

        String stored = redis.opsForValue().get(redis.keys("shf:copilot:idem:" + anasWorkspace + ":*").iterator()
                .next());
        assertThat(stored).startsWith("DONE|").endsWith("|" + messageId).doesNotContain("saldo", key);
    }

    @Test
    void continuingAConversationAppendsAndReadsInOrder() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        String conversationId = JsonPath.read(body(ask(anasWorkspace, key(), BALANCE)), "$.conversationId");

        MvcTestResult second = ask(anasWorkspace, key(), """
                {"question": "E o saldo de hoje?", "conversationId": "%s"}""".formatted(conversationId));

        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<String>read(body(second), "$.conversationId")).isEqualTo(conversationId);
        String conversation = body(mvc.get().uri(CONVERSATION, anasWorkspace, conversationId).with(ana()).exchange());
        assertThat(JsonPath.<List<String>>read(conversation, "$.messages[*].question"))
                .containsExactly("Qual é o meu saldo?", "E o saldo de hoje?");
        assertThat(JsonPath.<Integer>read(body(mvc.get().uri(CONVERSATIONS, anasWorkspace).with(ana()).exchange()),
                "$.items[0].messageCount")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from copilot_conversations", Long.class)).isEqualTo(1);
    }

    @Test
    void withoutHistoryNothingIsStoredAndTheConversationRoutesAreNotFound() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace, "COPILOT_DATA_USE", "copilot-data-v1");
        String foreign = UUID.randomUUID().toString();

        MvcTestResult asked = ask(anasWorkspace, key(), BALANCE);

        assertThat(asked).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<Object>read(body(asked), "$.conversationId")).isNull();
        assertThat(JsonPath.<Object>read(body(asked), "$.messageId")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from copilot_messages", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from copilot_conversations", Long.class)).isZero();

        String page = body(mvc.get().uri(CONVERSATIONS, anasWorkspace).with(ana()).exchange());
        assertThat(JsonPath.<Boolean>read(page, "$.historyEnabled")).isFalse();
        assertThat(JsonPath.<List<Object>>read(page, "$.items")).isEmpty();
        assertError(mvc.get().uri(CONVERSATION, anasWorkspace, foreign).with(ana()).exchange(), HttpStatus.NOT_FOUND,
                "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(mvc.delete().uri(CONVERSATION, anasWorkspace, foreign).with(ana()).exchange(),
                HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(ask(anasWorkspace, key(), """
                {"question": "E agora?", "conversationId": "%s"}""".formatted(foreign)), HttpStatus.NOT_FOUND,
                "COPILOT_CONVERSATION_NOT_FOUND");
        assertThat(mvc.delete().uri(CONVERSATIONS, anasWorkspace).with(ana())).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void aConversationOfAnotherPersonOrWorkspaceIsNotFoundEverywhere() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        grantHistory("sub-bob", "bob@example.com", bobsWorkspace);
        UUID anas = seed(anasWs, anaUser, "Conversa da Ana", Instant.now(), Instant.now().plus(90, ChronoUnit.DAYS));
        // Mesmo Workspace, outra pessoa (o usuário existe, a conversa é dele).
        UUID otherPerson = seed(anasWs, bobUser, "Conversa de outra pessoa", Instant.now(),
                Instant.now().plus(90, ChronoUnit.DAYS));
        long before = jdbc.queryForObject("select count(*) from copilot_messages", Long.class);

        // Bob, no próprio Workspace, com o id da conversa da Ana.
        assertError(get(bob(), bobsWorkspace, anas), HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(mvc.delete().uri(CONVERSATION, bobsWorkspace, anas).with(bob()).exchange(), HttpStatus.NOT_FOUND,
                "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(askAs(bob(), bobsWorkspace, key(), """
                {"question": "E agora?", "conversationId": "%s"}""".formatted(anas)), HttpStatus.NOT_FOUND,
                "COPILOT_CONVERSATION_NOT_FOUND");
        // Ana, no Workspace dela, com a conversa de outra pessoa.
        assertError(get(ana(), anasWorkspace, otherPerson), HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(mvc.delete().uri(CONVERSATION, anasWorkspace, otherPerson).with(ana()).exchange(),
                HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(ask(anasWorkspace, key(), """
                {"question": "E agora?", "conversationId": "%s"}""".formatted(otherPerson)), HttpStatus.NOT_FOUND,
                "COPILOT_CONVERSATION_NOT_FOUND");

        String list = body(mvc.get().uri(CONVERSATIONS, anasWorkspace).with(ana()).exchange());
        assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).containsExactly(anas.toString());
        assertThat(jdbc.queryForObject("select count(*) from copilot_messages", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from ai_usage", Long.class)).isZero();
        // Apagar tudo no Workspace da Ana não toca a conversa da outra pessoa.
        assertThat(mvc.delete().uri(CONVERSATIONS, anasWorkspace).with(ana())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForList("select id from copilot_conversations", UUID.class)).containsExactly(otherPerson);
    }

    // --- listagem ---

    @Test
    void listsFromTheNewestWithAnOpaqueCursorAndClampsTheLimit() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        Instant base = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        List<UUID> newestFirst = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            newestFirst.add(0, seed(anasWs, anaUser, "Conversa " + i, base.plusSeconds(i * 60L),
                    base.plus(90, ChronoUnit.DAYS)));
        }
        // Empate de last_message_at: desempata pelo id, sem repetir nem pular.
        UUID tieA = seed(anasWs, anaUser, "Empate A", base.plusSeconds(10 * 60L), base.plus(90, ChronoUnit.DAYS));
        UUID tieB = seed(anasWs, anaUser, "Empate B", base.plusSeconds(10 * 60L), base.plus(90, ChronoUnit.DAYS));
        List<String> expected = new ArrayList<>(List.of(tieA.toString(), tieB.toString()));
        expected.sort(java.util.Comparator.reverseOrder());
        newestFirst.forEach(id -> expected.add(id.toString()));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcTestResult result = cursor == null ? mvc.get().uri(CONVERSATIONS + "?limit=3", anasWorkspace)
                    .with(ana()).exchange()
                    : mvc.get().uri(CONVERSATIONS + "?limit=3&before={c}", anasWorkspace, cursor).with(ana())
                            .exchange();
            assertThat(result).hasStatus(HttpStatus.OK);
            String page = body(result);
            seen.addAll(JsonPath.<List<String>>read(page, "$.items[*].id"));
            cursor = JsonPath.read(page, "$.nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
        assertThat(JsonPath.<List<Object>>read(body(mvc.get().uri(CONVERSATIONS + "?limit=0", anasWorkspace)
                .with(ana()).exchange()), "$.items")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(body(mvc.get().uri(CONVERSATIONS + "?limit=500", anasWorkspace)
                .with(ana()).exchange()), "$.items")).hasSize(7);
        assertError(mvc.get().uri(CONVERSATIONS + "?before=nao-e-um-cursor", anasWorkspace).with(ana()).exchange(),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void expiredMessagesNeverComeBackEvenBeforeTheJobRuns() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        UUID allExpired = seed(anasWs, anaUser, "Toda expirada", old, old.plus(90, ChronoUnit.DAYS));
        UUID mixed = seed(anasWs, anaUser, "Mista", old, old.plus(90, ChronoUnit.DAYS));
        append(anasWs, anaUser, mixed, "Pergunta recente", Instant.now(), Instant.now().plus(90, ChronoUnit.DAYS));

        String page = body(mvc.get().uri(CONVERSATIONS, anasWorkspace).with(ana()).exchange());

        assertThat(JsonPath.<List<String>>read(page, "$.items[*].id")).containsExactly(mixed.toString());
        assertThat(JsonPath.<Integer>read(page, "$.items[0].messageCount")).isEqualTo(1);
        assertError(get(ana(), anasWorkspace, allExpired), HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(mvc.delete().uri(CONVERSATION, anasWorkspace, allExpired).with(ana()).exchange(),
                HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(ask(anasWorkspace, key(), """
                {"question": "E agora?", "conversationId": "%s"}""".formatted(allExpired)), HttpStatus.NOT_FOUND,
                "COPILOT_CONVERSATION_NOT_FOUND");
        String detail = body(mvc.get().uri(CONVERSATION, anasWorkspace, mixed).with(ana()).exchange());
        assertThat(JsonPath.<List<String>>read(detail, "$.messages[*].question")).containsExactly("Pergunta recente");
    }

    // --- exclusão ---

    @Test
    void deletesOneOrAllConversationsOfThePersonOnly() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        grantHistory("sub-bob", "bob@example.com", bobsWorkspace);
        Instant expires = Instant.now().plus(90, ChronoUnit.DAYS);
        UUID first = seed(anasWs, anaUser, "Primeira", Instant.now(), expires);
        UUID second = seed(anasWs, anaUser, "Segunda", Instant.now(), expires);
        UUID bobs = seed(new WorkspaceId(UUID.fromString(bobsWorkspace)), bobUser, "Do Bob", Instant.now(), expires);

        assertThat(mvc.delete().uri(CONVERSATION, anasWorkspace, first).with(ana())).hasStatus(HttpStatus.NO_CONTENT);
        assertError(get(ana(), anasWorkspace, first), HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertError(mvc.delete().uri(CONVERSATION, anasWorkspace, first).with(ana()).exchange(),
                HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from copilot_messages where conversation_id = ?::uuid",
                Long.class, first.toString())).as("as mensagens saem em cascata").isZero();

        assertThat(mvc.delete().uri(CONVERSATIONS, anasWorkspace).with(ana())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.delete().uri(CONVERSATIONS, anasWorkspace).with(ana()))
                .as("idempotente").hasStatus(HttpStatus.NO_CONTENT);
        assertError(get(ana(), anasWorkspace, second), HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        assertThat(jdbc.queryForList("select id from copilot_conversations", UUID.class)).containsExactly(bobs);
    }

    // --- replay ---

    @Test
    void aReplayWithHistoryReturnsTheStoredMessageAndOneWithoutItIsAlreadyAnswered() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        String key = key();
        String first = body(ask(anasWorkspace, key, BALANCE));
        long calls = jdbc.queryForObject("select count(*) from ai_usage", Long.class);

        MvcTestResult replay = ask(anasWorkspace, key, BALANCE);

        assertThat(replay).hasStatus(HttpStatus.OK);
        String again = body(replay);
        assertThat(JsonPath.<String>read(again, "$.messageId")).isEqualTo(JsonPath.<String>read(first, "$.messageId"));
        assertThat(JsonPath.<String>read(again, "$.requestId")).isEqualTo(JsonPath.<String>read(first, "$.requestId"));
        assertThat(JsonPath.<String>read(again, "$.conversationId"))
                .isEqualTo(JsonPath.<String>read(first, "$.conversationId"));
        assertThat(JsonPath.<Object>read(again, "$.answer")).isEqualTo(JsonPath.<Object>read(first, "$.answer"));
        assertThat(jdbc.queryForObject("select count(*) from ai_usage", Long.class))
                .as("replay não chama o modelo nem gasta quota").isEqualTo(calls);
        assertThat(jdbc.queryForObject("select count(*) from copilot_messages", Long.class)).isEqualTo(1);

        // Apagada a conversa, não há mais mensagem guardada: volta a ser 409.
        assertThat(mvc.delete().uri(CONVERSATIONS, anasWorkspace).with(ana())).hasStatus(HttpStatus.NO_CONTENT);
        assertError(ask(anasWorkspace, key, BALANCE), HttpStatus.CONFLICT, "COPILOT_ALREADY_ANSWERED");
    }

    // --- feedback ---

    @Test
    void feedbackIsAnUpsertOfTheLatestAndOnlyForTheOwnQuestions() throws Exception {
        grantConsent("sub-ana", "ana@example.com", anasWorkspace, "COPILOT_DATA_USE", "copilot-data-v1");
        grantConsent("sub-bob", "bob@example.com", bobsWorkspace, "COPILOT_DATA_USE", "copilot-data-v1");
        String anasRequest = JsonPath.read(body(ask(anasWorkspace, key(), BALANCE)), "$.requestId");
        String bobsRequest = JsonPath.read(body(askAs(bob(), bobsWorkspace, key(), BALANCE)), "$.requestId");

        assertThat(feedback(ana(), anasWorkspace, anasRequest, "{\"rating\": \"HELPFUL\"}"))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(feedback(ana(), anasWorkspace, anasRequest,
                "{\"rating\": \"NOT_HELPFUL\", \"reason\": \"WRONG_NUMBERS\"}")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForObject("select count(*) from copilot_feedback", Long.class)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select * from copilot_feedback where request_id = ?::uuid",
                anasRequest);
        assertThat(row).containsEntry("rating", "NOT_HELPFUL").containsEntry("reason", "WRONG_NUMBERS")
                .containsEntry("workspace_id", UUID.fromString(anasWorkspace))
                .containsEntry("user_id", anaUser.value());
        assertThat(row.get("intent")).as("sem histórico, o ledger não guarda a intenção").isNull();
        assertThat(row.get("versions").toString()).contains("intentPrompt");
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant())
                .isAfter(Instant.now().plus(89, ChronoUnit.DAYS));

        assertError(feedback(ana(), anasWorkspace, bobsRequest, "{\"rating\": \"HELPFUL\"}"), HttpStatus.NOT_FOUND,
                "COPILOT_REQUEST_NOT_FOUND");
        assertError(feedback(bob(), anasWorkspace, anasRequest, "{\"rating\": \"HELPFUL\"}"), HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND");
        assertError(feedback(bob(), bobsWorkspace, anasRequest, "{\"rating\": \"HELPFUL\"}"), HttpStatus.NOT_FOUND,
                "COPILOT_REQUEST_NOT_FOUND");
        assertError(feedback(ana(), anasWorkspace, UUID.randomUUID().toString(), "{\"rating\": \"HELPFUL\"}"),
                HttpStatus.NOT_FOUND, "COPILOT_REQUEST_NOT_FOUND");
        assertError(feedback(ana(), anasWorkspace, anasRequest, "{}"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(feedback(ana(), anasWorkspace, anasRequest, "{\"rating\": \"LOVED\"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(feedback(ana(), anasWorkspace, anasRequest,
                "{\"rating\": \"HELPFUL\", \"reason\": \"texto livre\"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("select rating from copilot_feedback", String.class))
                .as("o erro não mudou o feedback").isEqualTo("NOT_HELPFUL");
    }

    @Test
    void feedbackKeepsTheIntentFromTheStoredMessageAndWorksWithoutConsent() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        String request = JsonPath.read(body(ask(anasWorkspace, key(), BALANCE)), "$.requestId");

        assertThat(feedback(ana(), anasWorkspace, request, "{\"rating\": \"HELPFUL\"}"))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForObject("select intent from copilot_feedback", String.class)).isEqualTo("OVERVIEW");
        // Revogar o consentimento não impede o feedback: ele não manda nada ao modelo.
        assertThat(mvc.put().uri(WS + "/consents/COPILOT_DATA_USE", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"granted\": false}")).hasStatus(HttpStatus.OK);
        assertThat(feedback(ana(), anasWorkspace, request, "{\"rating\": \"NOT_HELPFUL\"}"))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    // --- revogação ---

    @Test
    void revokingHistoryDeletesThePersonsConversationsAndNobodyElses() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        grantHistory("sub-bob", "bob@example.com", bobsWorkspace);
        ask(anasWorkspace, key(), BALANCE);
        UUID bobs = seed(new WorkspaceId(UUID.fromString(bobsWorkspace)), bobUser, "Do Bob", Instant.now(),
                Instant.now().plus(90, ChronoUnit.DAYS));

        assertThat(mvc.put().uri(WS + "/consents/COPILOT_HISTORY", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"granted\": false}")).hasStatus(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from copilot_conversations where user_id = ?", Long.class, anaUser.value()))
                .isZero());
        assertThat(jdbc.queryForList("select id from copilot_conversations", UUID.class)).containsExactly(bobs);
        assertThat(jdbc.queryForObject("select count(*) from copilot_messages where user_id = ?", Long.class,
                anaUser.value())).isZero();
    }

    @Test
    void revokingDataUseCascadesToTheHistoryAndDeletesIt() throws Exception {
        grantHistory("sub-ana", "ana@example.com", anasWorkspace);
        ask(anasWorkspace, key(), BALANCE);
        assertThat(jdbc.queryForObject("select count(*) from copilot_conversations", Long.class)).isEqualTo(1);

        assertThat(mvc.put().uri(WS + "/consents/COPILOT_DATA_USE", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("{\"granted\": false}")).hasStatus(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from copilot_conversations", Long.class)).isZero());
    }

    @Test
    void theConsumerIgnoresOtherPurposesAndAReDeliveredEventIsANoOp() {
        Instant expires = Instant.now().plus(90, ChronoUnit.DAYS);
        seed(anasWs, anaUser, "Conversa", Instant.now(), expires);
        UUID event = UUID.randomUUID();

        assertThat(revocation.execute(UUID.randomUUID(), anasWs, anaUser, "COPILOT_DATA_USE", Instant.now()))
                .isFalse();
        assertThat(jdbc.queryForObject("select count(*) from copilot_conversations", Long.class))
                .as("outro propósito não apaga").isEqualTo(1);

        assertThat(revocation.execute(event, anasWs, anaUser, "COPILOT_HISTORY", Instant.now())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from copilot_conversations", Long.class)).isZero();

        seed(anasWs, anaUser, "Outra conversa depois", Instant.now(), expires);
        assertThat(revocation.execute(event, anasWs, anaUser, "COPILOT_HISTORY", Instant.now()))
                .as("o mesmo evento entregue de novo não tem efeito").isFalse();
        assertThat(jdbc.queryForObject("select count(*) from copilot_conversations", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer = ? and event_id = ?::uuid",
                Long.class, DeleteConversationsOnRevocation.CONSUMER, event.toString())).isEqualTo(1);
    }

    @Test
    void aLateRevocationEventOnlyDeletesWhatWasCreatedUpToItsOccurredAt() {
        Instant expires = Instant.now().plus(90, ChronoUnit.DAYS);
        Instant revokedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        UUID old = seed(anasWs, anaUser, "Antiga", revokedAt.minus(1, ChronoUnit.HOURS), expires);
        UUID mixed = seed(anasWs, anaUser, "Mista antiga", revokedAt.minus(30, ChronoUnit.MINUTES), expires);
        append(anasWs, anaUser, mixed, "Depois do novo consentimento", revokedAt.plus(1, ChronoUnit.HOURS), expires);
        UUID fresh = seed(anasWs, anaUser, "Nova", revokedAt.plus(90, ChronoUnit.MINUTES), expires);

        assertThat(revocation.execute(UUID.randomUUID(), anasWs, anaUser, "COPILOT_HISTORY", revokedAt)).isTrue();

        assertThat(jdbc.queryForList("select id from copilot_conversations", UUID.class))
                .containsExactlyInAnyOrder(mixed, fresh).doesNotContain(old);
        assertThat(jdbc.queryForList("select question from copilot_messages", String.class))
                .containsExactlyInAnyOrder("Depois do novo consentimento", "Nova");
        assertThat(jdbc.queryForObject("select title from copilot_conversations where id = ?", String.class, mixed))
                .as("título regravado a partir da primeira pergunta que restou")
                .isEqualTo("Depois do novo consentimento");
        assertThat(jdbc.queryForObject("select title from copilot_conversations where id = ?", String.class, fresh))
                .isEqualTo("Nova");
    }

    // --- retenção ---

    @Test
    void theRetentionJobDeletesExpiredMessagesEmptyConversationsAndExpiredFeedbackOnly() {
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        UUID allExpired = seed(anasWs, anaUser, "Toda expirada", old, old.plus(90, ChronoUnit.DAYS));
        UUID mixed = seed(anasWs, anaUser, "Mista", old, old.plus(90, ChronoUnit.DAYS));
        append(anasWs, anaUser, mixed, "Recente", Instant.now(), Instant.now().plus(90, ChronoUnit.DAYS));
        UUID fresh = seed(anasWs, anaUser, "Recente", Instant.now(), Instant.now().plus(90, ChronoUnit.DAYS));
        UUID expiredFeedback = UUID.randomUUID();
        UUID liveFeedback = UUID.randomUUID();
        feedbackStore.upsert(new FeedbackStore.Feedback(expiredFeedback, anasWs, anaUser, FeedbackRating.HELPFUL, null,
                null, Map.of(), old, old.plus(90, ChronoUnit.DAYS)));
        feedbackStore.upsert(new FeedbackStore.Feedback(liveFeedback, anasWs, anaUser, FeedbackRating.HELPFUL, null,
                null, Map.of(), Instant.now(), Instant.now().plus(90, ChronoUnit.DAYS)));

        var round = purge.execute();

        assertThat(round.messages()).isEqualTo(2);
        assertThat(round.conversations()).isEqualTo(1);
        assertThat(round.feedback()).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from copilot_conversations", UUID.class))
                .containsExactlyInAnyOrder(mixed, fresh).doesNotContain(allExpired);
        assertThat(jdbc.queryForObject("select count(*) from copilot_messages", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select request_id from copilot_feedback", UUID.class))
                .containsExactly(liveFeedback);
        assertThat(purge.execute()).as("nada mais a apagar").isEqualTo(new com.smarthealthfinance.copilot
                .application.dto.PurgeRound(0, 0, 0));
    }

    // --- apoio ---

    private UUID seed(WorkspaceId workspace, UserId user, String title, Instant createdAt, Instant expiresAt) {
        UUID conversation = store.createConversation(workspace, user, title, createdAt);
        assertThat(store.addMessage(workspace, user, conversation, message(title, createdAt, expiresAt))).isTrue();
        return conversation;
    }

    private void append(WorkspaceId workspace, UserId user, UUID conversation, String question, Instant createdAt,
                        Instant expiresAt) {
        assertThat(store.addMessage(workspace, user, conversation, message(question, createdAt, expiresAt))).isTrue();
    }

    private static NewMessage message(String question, Instant createdAt, Instant expiresAt) {
        CopilotResult result = new CopilotResult(UUID.randomUUID(), ResultCode.OK, Intent.OVERVIEW,
                RenderedAnswer.plain(AnswerType.ANALYSIS, "Resposta fixa", List.of()), AnswerConfidence.HIGH,
                ValidationStatus.VALID, null, null, Map.of("answerPrompt", "answer-v2@test"));
        return new NewMessage(UuidV7.generate(createdAt), question, result, createdAt, expiresAt);
    }

    private UserId user(String email) {
        return new UserId(jdbc.queryForObject("select id from users where email = ?", UUID.class, email));
    }

    private MvcTestResult ask(String workspace, String key, String body) {
        return askAs(ana(), workspace, key, body);
    }

    private MvcTestResult askAs(RequestPostProcessor who, String workspace, String key, String body) {
        return mvc.post().uri(QUESTIONS, workspace).with(who).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body).exchange();
    }

    private MvcTestResult get(RequestPostProcessor who, String workspace, UUID conversation) {
        return mvc.get().uri(CONVERSATION, workspace, conversation).with(who).exchange();
    }

    private MvcTestResult feedback(RequestPostProcessor who, String workspace, String requestId, String json) {
        return mvc.put().uri(FEEDBACK, workspace, requestId).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(json).exchange();
    }

    private void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(body(result), "$.code")).isEqualTo(code);
    }

    private void grantHistory(String subject, String email, String workspace) {
        grantConsent(subject, email, workspace, "COPILOT_DATA_USE", "copilot-data-v1");
        grantConsent(subject, email, workspace, "COPILOT_HISTORY", "copilot-history-v1");
    }

    private void grantConsent(String subject, String email, String workspace, String purpose, String terms) {
        assertThat(mvc.put().uri(WS + "/consents/" + purpose, workspace).with(request(subject, email))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"granted\": true, \"termsVersion\": \"" + terms + "\"}")).hasStatus(HttpStatus.OK);
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
