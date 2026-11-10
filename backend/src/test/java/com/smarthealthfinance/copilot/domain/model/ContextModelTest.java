package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.CopilotFixtures;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invariantes do contexto {@code context-v1} e dos pseudônimos (ADR-0039 §3, §5). */
class ContextModelTest {

    @Test
    void valuesValidateTheirFormatPerUnit() {
        assertThatThrownBy(() -> value("1,5", ValueUnit.MONEY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value("1.5", ValueUnit.COUNT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value("2026-13", ValueUnit.MONTH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value("10/11/2026", ValueUnit.DATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value("x".repeat(121), ValueUnit.TEXT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(value("-12.3456", ValueUnit.MONEY).decimal()).isEqualByComparingTo("-12.3456");
    }

    @Test
    void labelsCannotCarryNumbersExceptPseudonyms() {
        assertThatThrownBy(() -> new ContextValue("v1", "Gasto dos últimos 6 meses", "1", ValueUnit.MONEY,
                ValueKind.FACT, null, "s")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ContextValue("v1", "Fatura de cartao_12", "1", ValueUnit.MONEY, ValueKind.FACT, null, "s")
                .label()).isEqualTo("Fatura de cartao_12");
    }

    @Test
    void contextRejectsDuplicatedRefs() {
        ContextValue one = value("1", ValueUnit.COUNT);
        assertThatThrownBy(() -> new CopilotContext(com.smarthealthfinance.copilot.domain.enums.Intent.CARDS,
                CopilotFixtures.TODAY, CopilotFixtures.BRL, java.util.List.of(one, one), DataQuality.EMPTY,
                java.util.List.of(), java.util.List.of())).hasMessageContaining("duplicated ref");
    }

    @Test
    void pseudonymsAreStablePerItemAndNumberedPerKind() {
        Pseudonyms pseudonyms = new Pseudonyms();

        assertThat(pseudonyms.assign(PseudonymKind.CARD, "a", "VISA", "Nubank")).isEqualTo("cartao_1");
        assertThat(pseudonyms.assign(PseudonymKind.GOAL, "b", "PURCHASE", "Viagem")).isEqualTo("objetivo_1");
        assertThat(pseudonyms.assign(PseudonymKind.CARD, "c", "ELO", "Inter")).isEqualTo("cartao_2");
        assertThat(pseudonyms.assign(PseudonymKind.CARD, "a", "VISA", "Nubank")).isEqualTo("cartao_1");
        assertThat(pseudonyms.find(PseudonymKind.GOAL, "cartao_1")).isEmpty();
        assertThat(pseudonyms.toDisplay("cartao_2 e cartao_9")).isEqualTo("Inter e cartao_9");
        assertThat(pseudonyms.find("cartao_1").orElseThrow().toString()).doesNotContain("Nubank", "a]");
    }

    @Test
    void offeredActionsNeedTargetsOnlyForItemScreens() {
        assertThatThrownBy(() -> new OfferedAction(ActionKind.OPEN_INVOICE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OfferedAction(ActionKind.OPEN_HEALTH, "cartao_1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftsNeverExposeTheModelTextInToString() {
        AnswerDraft draft = new AnswerDraft("saldo secreto", "analysis", java.util.List.of(), java.util.List.of(),
                java.util.List.of());

        assertThat(draft.toString()).doesNotContain("saldo secreto");
    }

    @Test
    void recordsCarryingValuesOrNamesNeverPrintThem() {
        ContextValue value = new ContextValue("v1", "Saldo de cartao_1", "98765.43", ValueUnit.MONEY,
                ValueKind.FACT, null, "s");
        IntentDecision decision = new IntentDecision(com.smarthealthfinance.copilot.domain.enums.Intent.GOALS,
                null, null, "objetivo_1", new IntentDecision.Scenario("98765.43", null, null, null));
        RenderedAnswer.Value rendered = new RenderedAnswer.Value("v1", "Viagem da Ana", "R$ 98.765,43",
                ValueKind.FACT, null, "s");
        RenderedAnswer.Action action = new RenderedAnswer.Action(ActionKind.OPEN_GOAL, "id-123", "Viagem da Ana");

        for (Object record : java.util.List.of(value, decision, decision.scenario(), rendered, action,
                CopilotFixtures.context(), new AnswerDraft.Action("OPEN_GOAL", "objetivo_1"))) {
            assertThat(record.toString()).doesNotContain("98765", "98.765", "Viagem", "id-123", "Saldo",
                    "objetivo_1", "1.234");
        }
    }

    private static ContextValue value(String value, ValueUnit unit) {
        return new ContextValue("v1", "Valor", value, unit, ValueKind.FACT, null, "s");
    }
}
