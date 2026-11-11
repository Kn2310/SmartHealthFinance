package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Numeração das refs e quadro de decomposição (ADR-0039 §5, ADR-0040 §3): o corte nunca deixa ref sem valor. */
class ContextBuilderTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private static ContextValue value(String label) {
        return ContextValue.draft(label, "10", ValueUnit.MONEY, ValueKind.FACT, null, "test-v1");
    }

    private static FinancialTool.Result result(FinancialTool.BreakdownDraft breakdown, ContextValue... values) {
        return new FinancialTool.Result(FinancialTool.Status.DATA, List.of(values), DataQuality.EMPTY, List.of(),
                List.of(), breakdown);
    }

    private static CopilotContext build(FinancialTool.Result result, int ceiling) {
        return ContextBuilder.build(Intent.CARDS, LocalDate.of(2026, 11, 10), BRL, result, ceiling);
    }

    @Test
    void breakdownPositionsBecomeRefsInThePriorityOrder() {
        var draft = new FinancialTool.BreakdownDraft("Fatores", List.of(
                new FinancialTool.BreakdownDraft.Row("Alimentação fora", 1, null),
                new FinancialTool.BreakdownDraft.Row("Parcela de Loja Tech (3/10)", 2, "3/10")), 0);

        CopilotContext context = build(result(draft, value("Diferença"), value("Fator"), value("Fator")), 12_000);

        assertThat(context.breakdown().totalRef()).isEqualTo("v1");
        assertThat(context.breakdown().rows()).extracting(row -> row.valueRef()).containsExactly("v2", "v3");
        assertThat(context.breakdown().rows().get(1).detail()).isEqualTo("3/10");
        assertThat(context.values()).extracting(ContextValue::ref).containsExactly("v1", "v2", "v3");
    }

    @Test
    void rowsCutByTheContextCeilingLeaveTheBreakdownAndTheTotalCutDropsIt() {
        var draft = new FinancialTool.BreakdownDraft("Fatores", List.of(
                new FinancialTool.BreakdownDraft.Row("A", 1, null),
                new FinancialTool.BreakdownDraft.Row("B", 2, null)), 0);
        List<ContextValue> many = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            many.add(value("Valor"));
        }

        // Cada valor custa 104 caracteres (~26 tokens): com teto de 60 cabem dois, e a linha B é cortada.
        CopilotContext cut = build(result(draft, many.toArray(new ContextValue[0])), 60);
        assertThat(cut.values()).hasSize(2);
        assertThat(cut.breakdown().rows()).extracting(row -> row.label()).containsExactly("A");

        // Com teto de 30 só cabe o primeiro valor: sem linhas, o quadro some.
        assertThat(build(result(draft, many.toArray(new ContextValue[0])), 30).breakdown()).isNull();

        // Total cortado (posição 2 com teto de 60): o quadro some, para nunca citar ref inexistente.
        var totalCut = new FinancialTool.BreakdownDraft("Fatores", List.of(
                new FinancialTool.BreakdownDraft.Row("A", 0, null)), 2);
        assertThat(build(result(totalCut, many.toArray(new ContextValue[0])), 60).breakdown()).isNull();
    }

    @Test
    void aBreakdownWithoutTotalKeepsNullTotalAndNoDraftMeansNoBreakdown() {
        var draft = new FinancialTool.BreakdownDraft("Notas", List.of(
                new FinancialTool.BreakdownDraft.Row("Fluxo de caixa", 0, null)), null);

        assertThat(build(result(draft, value("Nota")), 12_000).breakdown().totalRef()).isNull();
        assertThat(build(result(null, value("Nota")), 12_000).breakdown()).isNull();
    }
}
