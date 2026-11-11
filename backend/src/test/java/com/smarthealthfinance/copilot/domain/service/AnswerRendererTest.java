package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.CopilotFixtures;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.smarthealthfinance.copilot.domain.CopilotFixtures.BRL;
import static com.smarthealthfinance.copilot.domain.CopilotFixtures.CARD_ID;
import static com.smarthealthfinance.copilot.domain.CopilotFixtures.CARD_NICKNAME;
import static org.assertj.core.api.Assertions.assertThat;

/** Renderização (ADR-0039 §7.1): o valor vem só do contexto, formatado em pt-BR, com rótulo de estimado. */
class AnswerRendererTest {

    private final CopilotContext context = CopilotFixtures.context();
    private final Pseudonyms pseudonyms = CopilotFixtures.pseudonyms();

    @Test
    void replacesReferencesWithFormattedValuesAndPseudonymsWithNames() {
        AnswerDraft draft = new AnswerDraft("A fatura de cartao_1 é {{v:v1}}, vence em {{v:v2}} e usa {{v:v3}}.",
                "analysis", List.of(), List.of("A próxima: {{v:v4}} em {{v:v5}}."), List.of());

        RenderedAnswer rendered = AnswerRenderer.render(draft, context, pseudonyms);

        assertThat(rendered.type()).isEqualTo(AnswerType.ANALYSIS);
        assertThat(rendered.text()).isEqualTo("A fatura de " + CARD_NICKNAME
                + " é R$ 1.234,56, vence em 15/11/2026 e usa 36,1%.");
        assertThat(rendered.explanation())
                .containsExactly("A próxima: R$ 980,40 (estimado) em dezembro de 2026.");
        assertThat(rendered.financialData()).extracting(RenderedAnswer.Value::ref)
                .containsExactly("v1", "v2", "v3", "v4", "v5");
        assertThat(rendered.financialData().get(3).kind()).isEqualTo(ValueKind.ESTIMATED);
        assertThat(rendered.financialData().getFirst().label()).isEqualTo("Fatura atual de " + CARD_NICKNAME);
    }

    @Test
    void textValuesAreInsertedVerbatimAndNeverReinterpreted() {
        CopilotContext tricky = new CopilotContext(context.intent(), context.today(), BRL, List.of(
                new ContextValue("v1", "Comerciante", "{{v:v2}} cartao_1", ValueUnit.TEXT, ValueKind.FACT, null,
                        "analytics-v1"),
                new ContextValue("v2", "Valor", "10", ValueUnit.MONEY, ValueKind.FACT, null, "analytics-v1")),
                context.quality(), List.of(), List.of());

        RenderedAnswer rendered = AnswerRenderer.render(new AnswerDraft("Maior: {{v:v1}}.", "analysis", List.of(),
                List.of(), List.of()), tricky, pseudonyms);

        assertThat(rendered.text()).isEqualTo("Maior: {{v:v2}} cartao_1.");
    }

    @Test
    void keepsOnlyOfferedActionsAndResolvesTargetsToInternalIds() {
        AnswerDraft draft = new AnswerDraft("Veja {{v:v1}}.", "analysis", List.of("v1"), List.of(), List.of(
                new AnswerDraft.Action("OPEN_INVOICE", "cartao_1"),
                new AnswerDraft.Action("OPEN_INVOICE", "cartao_9"),
                new AnswerDraft.Action("TRANSFER_MONEY", "cartao_1"),
                new AnswerDraft.Action("OPEN_GOAL", "objetivo_1"),
                new AnswerDraft.Action("OPEN_FORECAST", null),
                new AnswerDraft.Action("OPEN_INVOICE", "cartao_1")));

        RenderedAnswer rendered = AnswerRenderer.render(draft, context, pseudonyms);

        assertThat(rendered.actions()).containsExactly(
                new RenderedAnswer.Action(ActionKind.OPEN_INVOICE, CARD_ID, CARD_NICKNAME),
                new RenderedAnswer.Action(ActionKind.OPEN_FORECAST, null, null));
    }

    @Test
    void formatsEveryUnitInPortuguese() {
        assertThat(ValueFormatter.format(value("-120", ValueUnit.MONEY), BRL)).isEqualTo("-R$ 120,00");
        assertThat(ValueFormatter.format(value("1234567.8949", ValueUnit.MONEY), BRL)).isEqualTo("R$ 1.234.567,89");
        assertThat(ValueFormatter.format(value("0.004", ValueUnit.MONEY), BRL)).isEqualTo("R$ 0,00");
        assertThat(ValueFormatter.format(value("-0.004", ValueUnit.MONEY), BRL)).isEqualTo("R$ 0,00");
        assertThat(ValueFormatter.format(value("0.125", ValueUnit.MONEY), BRL)).as("empate: HALF_EVEN")
                .isEqualTo("R$ 0,12");
        assertThat(ValueFormatter.format(value("0.135", ValueUnit.MONEY), BRL)).isEqualTo("R$ 0,14");
        assertThat(ValueFormatter.format(value("-2.505", ValueUnit.MONEY), BRL)).isEqualTo("-R$ 2,50");
        assertThat(ValueFormatter.format(value("10", ValueUnit.MONEY), java.util.Currency.getInstance("USD")))
                .isEqualTo("USD 10,00");
        assertThat(ValueFormatter.format(value("-4.25", ValueUnit.PERCENT), BRL)).isEqualTo("-4,25%");
        assertThat(ValueFormatter.format(value("12", ValueUnit.PERCENT), BRL)).isEqualTo("12%");
        assertThat(ValueFormatter.format(value("1500", ValueUnit.COUNT), BRL)).isEqualTo("1.500");
        assertThat(ValueFormatter.format(value("72", ValueUnit.SCORE), BRL)).isEqualTo("72");
        assertThat(ValueFormatter.format(value("2026-03", ValueUnit.MONTH), BRL)).isEqualTo("março de 2026");
        assertThat(ValueFormatter.format(value("2026-03-05", ValueUnit.DATE), BRL)).isEqualTo("05/03/2026");
        assertThat(ValueFormatter.format(new ContextValue("v1", "Simulado", "500", ValueUnit.MONEY,
                ValueKind.SIMULATED, null, "goals-v1"), BRL)).isEqualTo("R$ 500,00 (simulado)");
    }

    private static ContextValue value(String value, ValueUnit unit) {
        return new ContextValue("v1", "Valor", value, unit, ValueKind.FACT, null, "test-v1");
    }
}
