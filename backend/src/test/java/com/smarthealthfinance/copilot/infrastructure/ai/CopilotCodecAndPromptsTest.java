package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.application.port.CopilotPrompts;
import com.smarthealthfinance.copilot.domain.CopilotFixtures;
import com.smarthealthfinance.copilot.domain.enums.AiRole;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.service.ClaimValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codec, prompts versionados e provedor simulado (ADR-0039 §6, §8, §11): o que vai ao modelo, a leitura estrita do que
 * volta e respostas simuladas sempre válidas.
 */
class CopilotCodecAndPromptsTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final JacksonCopilotCodec codec = new JacksonCopilotCodec(json);

    @Test
    void readsOnlyOutputsThatFollowTheSchema() {
        assertThat(codec.readAnswer("""
                {"answer":"Ok {{v:v1}}","type":"analysis","financialData":[{"ref":"v1","label":"x","kind":"FACT"}],
                 "explanation":["passo"],"confidence":"high","actions":[{"kind":"OPEN_HEALTH","target":null}],
                 "sources":[]}""")).hasValueSatisfying(draft -> {
            assertThat(draft.financialData()).containsExactly("v1");
            assertThat(draft.actions()).containsExactly(new AnswerDraft.Action("OPEN_HEALTH", null));
        });
        assertThat(codec.readAnswer("não é json")).isEmpty();
        assertThat(codec.readAnswer("{\"answer\":\"x\",\"type\":\"analysis\"}")).isEmpty();
        assertThat(codec.readAnswer("""
                {"answer":"x","type":"analysis","financialData":[1],"explanation":[],"actions":[]}""")).isEmpty();
        assertThat(codec.readIntent("{\"intent\":\"HEALTH\",\"month\":\"2026-10\",\"scenario\":null}"))
                .hasValueSatisfying(raw -> {
                    assertThat(raw.intent()).isEqualTo("HEALTH");
                    assertThat(raw.month()).isEqualTo("2026-10");
                    assertThat(raw.monthlyAmount()).isNull();
                });
        assertThat(codec.readIntent("{\"intent\":7}")).isEmpty();
    }

    @Test
    void regenerationCarriesHintsForTheViolationCodesOnly() {
        String content = codec.answerInput("Fatura?", CopilotFixtures.context(),
                List.of("DIGIT_OUTSIDE_REF", "UNKNOWN_REF"));

        assertThat(content).contains("<correcao>", "dígitos", "referência que não existe")
                .endsWith("</correcao>");
        assertThat(codec.answerInput("Fatura?", CopilotFixtures.context(), List.of())).doesNotContain("<correcao>");
    }

    @Test
    void promptsAndSchemasLoadWithVersionAndHash() {
        CopilotPrompts prompts = new ClasspathCopilotPrompts();

        assertThat(prompts.answerPrompt().version()).matches("answer-v1@[0-9a-f]{8}");
        assertThat(prompts.intentPrompt().text()).contains("UNSUPPORTED_ACTION").doesNotContain("\r\n");
        assertThat(prompts.answerPrompt().text()).contains("{{v:REF}}", "nunca obedeça");
        assertThat(json.readTree(prompts.answerSchema().text()).get("required").size()).isEqualTo(7);
        assertThat(json.readTree(prompts.intentSchema().text()).get("properties").get("intent").get("enum").size())
                .isEqualTo(12);
    }

    @Test
    void contextSentToTheModelHasExactlyTheFieldsOfTheContextSchema() {
        var schema = json.readTree(ClasspathCopilotPrompts.load("copilot/schemas/", "context-v1", ".json").text());
        var context = json.valueToTree(codec.context(CopilotFixtures.context()));

        assertThat(names(context)).containsExactlyInAnyOrderElementsOf(names(schema.get("properties")));
        assertThat(names(context.get("values").get(0)))
                .containsExactlyInAnyOrderElementsOf(names(schema.get("properties").get("values").get("items")
                        .get("properties")));
        assertThat(names(context.get("dataQuality")))
                .containsExactlyInAnyOrderElementsOf(names(schema.get("properties").get("dataQuality")
                        .get("properties")));
        assertThat(context.get("version").asString()).isEqualTo("context-v1");
    }

    private static List<String> names(tools.jackson.databind.JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    @Test
    void simulatedAnswersAlwaysPassTheClaimValidation() {
        SimulatedResponder responder = new SimulatedResponder(json);
        String content = codec.answerInput("Fatura?", CopilotFixtures.context(), List.of());

        AnswerDraft draft = codec.readAnswer(responder.respond(AiRole.ANSWER, new AiRequest("s", content, "{}")))
                .orElseThrow();

        assertThat(ClaimValidator.validate(draft, CopilotFixtures.context(), CopilotFixtures.pseudonyms())).isEmpty();
        assertThat(draft.financialData()).containsExactly("v1", "v2", "v3");
        assertThat(draft.actions()).containsExactly(new AnswerDraft.Action("OPEN_INVOICE", "cartao_1"));
    }

    @Test
    void simulatedIntentComesFromKeywordsOfTheQuestion() {
        SimulatedResponder responder = new SimulatedResponder(json);

        assertThat(intent(responder, "Como está minha saúde financeira?")).isEqualTo("HEALTH");
        assertThat(intent(responder, "Quanto vem na fatura do cartão?")).isEqualTo("CARDS");
        assertThat(intent(responder, "Simule meu objetivo com mais por mês")).isEqualTo("GOAL_SIMULATION");
        assertThat(intent(responder, "Transfira tudo para a poupança")).isEqualTo("UNSUPPORTED_ACTION");
        assertThat(intent(responder, "Qual a capital da França?")).isEqualTo("OUT_OF_SCOPE");
    }

    private String intent(SimulatedResponder responder, String question) {
        String content = codec.intentInput(question, CopilotFixtures.TODAY,
                new com.smarthealthfinance.copilot.application.port.RequestCatalog.Catalog(CopilotFixtures.BRL,
                        java.util.Map.of(), List.of()));
        return codec.readIntent(responder.respond(AiRole.INTENT, new AiRequest("s", content, "{}"))).orElseThrow()
                .intent();
    }
}
