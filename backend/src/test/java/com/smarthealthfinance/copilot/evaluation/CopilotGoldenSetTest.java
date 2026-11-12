package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.model.ModelPrice;
import com.smarthealthfinance.copilot.domain.model.PricingTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden set {@value GoldenSet#VERSION} no CI (ADR-0042 §2): cada caso roda pelo pipeline real do Copilot
 * ({@link GoldenHarness}) com as fixtures do caso e a saída roteirizada do modelo; o provedor é o simulado, sem rede
 * e sem chave. Roda em {@code ./mvnw test} (e portanto no {@code verify}); um caso que falha derruba o build. Mudar
 * prompt, esquema ou pipeline exige passar este teste (ADR-0039 §11). Não precisa de container, por isso é {@code *Test}.
 */
class CopilotGoldenSetTest {

    private static final PricingTable PRICING = PricingTable.of("pricing-golden", List.of(
            zero(ScriptedGateway.INTENT_MODEL), zero(ScriptedGateway.ANSWER_MODEL)));

    private static ModelPrice zero(String model) {
        BigDecimal zero = BigDecimal.ZERO;
        return new ModelPrice(model, new ModelPrice.Rates(zero, zero, zero, zero), 0, null);
    }

    static Stream<String> caseIds() {
        return GoldenSet.cases().stream().map(GoldenCase::id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseIds")
    void goldenCase(String id) {
        GoldenCase golden = GoldenSet.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        ScriptedGateway gateway = new ScriptedGateway(golden);

        GoldenHarness.Outcome outcome = GoldenHarness.run(golden, gateway, GoldenHarness.fixedClock(), PRICING);
        List<GoldenChecks.Failure> failures = GoldenChecks.checkScripted(golden, outcome, gateway);

        assertThat(failures).as("golden case %s (%s)", golden.id(), golden.group()).isEmpty();
    }

    @Test
    void everyFileIsInTheSchemaAndTheIdsAreUnique() {
        List<GoldenSet.File> files = GoldenSet.load();

        assertThat(files).extracting(GoldenSet.File::group)
                .contains("intents", "09-dados-de-exemplo", "adversarial", "insufficient-data", "continuity");
        assertThat(GoldenSet.duplicatedIds(files)).isEmpty();
        assertThat(files).allSatisfy(file -> assertThat(file.cases()).isNotEmpty());
    }

    @Test
    void theSchemaRejectsAMalformedCase() {
        MiniJsonSchema schema = new MiniJsonSchema(json("""
                {"type":"object","required":["id","expect"],"additionalProperties":false,
                 "properties":{"id":{"type":"string","pattern":"^[a-z]+$"},
                               "expect":{"type":"object","properties":{"code":{"enum":["OK"]}}}}}"""));

        assertThat(schema.validate(json("{\"id\":\"ab\",\"expect\":{\"code\":\"OK\"}}"))).isEmpty();
        assertThat(schema.validate(json("{\"id\":\"AB\",\"expect\":{\"code\":\"X\"},\"extra\":1}")))
                .hasSize(3);
        assertThat(schema.validate(json("{\"id\":\"ab\"}"))).singleElement().asString().contains("expect");
    }

    @Test
    void coversEveryIntentAndEveryResultCodeThePipelineProduces() {
        List<GoldenCase> cases = GoldenSet.cases();

        Set<String> intents = cases.stream().map(c -> c.expect().intent()).collect(Collectors.toSet());
        assertThat(intents).containsAll(EnumSet.allOf(Intent.class).stream().map(Enum::name).toList());
        Set<String> codes = cases.stream().map(c -> c.expect().code()).collect(Collectors.toSet());
        assertThat(codes).contains(ResultCode.OK.name(), ResultCode.SAFE_MODE.name(),
                ResultCode.INSUFFICIENT_DATA.name(), ResultCode.OUT_OF_SCOPE.name(),
                ResultCode.PROVIDER_UNAVAILABLE.name());
    }

    @Test
    void coversTheOutOfScopeAndActionRefusalsWithAndWithoutEntryContext() {
        List<GoldenCase> cases = GoldenSet.cases();

        for (String intent : List.of("OUT_OF_SCOPE", "UNSUPPORTED_ACTION")) {
            assertThat(cases).filteredOn(c -> intent.equals(c.expect().intent())).anyMatch(c -> c.context() == null);
        }
        assertThat(cases).filteredOn(c -> "UNSUPPORTED_ACTION".equals(c.expect().intent()))
                .anyMatch(c -> c.context() != null);
    }

    @Test
    void everyCaseWithAContextHasTheEntryFixtureAndEveryOkCaseHasAnAnswerScript() {
        for (GoldenCase golden : GoldenSet.cases()) {
            if (golden.context() != null && golden.expect().error() == null) {
                assertThat(golden.fixtures().entry()).as(golden.id()).isNotNull();
            }
            if ("OK".equals(golden.expect().code())) {
                assertThat(golden.scripted()).as(golden.id()).isNotNull();
                assertThat(golden.scripted().answers()).as(golden.id()).isNotEmpty();
            }
        }
    }

    private static JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }
}
