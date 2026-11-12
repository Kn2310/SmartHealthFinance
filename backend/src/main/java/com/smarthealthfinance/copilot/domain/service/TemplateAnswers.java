package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.AnswerSegment;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;

import java.util.List;

/**
 * Respostas determinísticas, sem o modelo de resposta (ADR-0039 §4, §5, §7.3, §12): modo seguro com os valores do
 * contexto, fora de escopo, pedido de ação e dados insuficientes. Nenhum número fora dos valores calculados. Todas
 * saem também como segmentos tipados (ADR-0040 §3).
 */
public final class TemplateAnswers {

    /** Quantos valores do contexto o modo seguro mostra (os primeiros, na prioridade da intenção). */
    public static final int SAFE_MODE_VALUES = 8;

    private TemplateAnswers() {
    }

    /** Modo seguro (§7.3): os valores já calculados, sem explicação do modelo. */
    public static RenderedAnswer safeMode(CopilotContext context, Pseudonyms pseudonyms) {
        List<RenderedAnswer.Value> values = context.values().stream()
                .limit(SAFE_MODE_VALUES)
                .map(value -> AnswerRenderer.value(value, context, pseudonyms))
                .toList();
        List<String> lines = values.stream().map(value -> value.label() + ": " + value.formatted()).toList();
        List<List<AnswerSegment>> lineSegments = values.stream()
                .map(value -> new AnswerRenderer.Segments().text(value.label() + ": ").value(value.ref()).build())
                .toList();
        String text = "Não consegui escrever uma explicação agora. Estes são os dados calculados pelo app sobre "
                + subject(context.intent()) + ".";
        return new RenderedAnswer(AnswerType.ANALYSIS, text, List.of(new AnswerSegment.Text(text)), lines,
                lineSegments, values, AnswerRenderer.resolve(context.actions(), pseudonyms), null, List.of());
    }

    public static RenderedAnswer outOfScope() {
        return RenderedAnswer.plain(AnswerType.OUT_OF_SCOPE, "Posso ajudar só com as suas finanças neste app: saldo, "
                + "gastos e receitas, categorias, comerciantes, saúde financeira, insights, cartões, previsão e "
                + "objetivos.", List.of());
    }

    /** Pedido de transferir, pagar ou editar (§12): o Copilot só lê e simula; a ação vira link quando há tela. */
    public static RenderedAnswer unsupportedAction(List<OfferedAction> links, Pseudonyms pseudonyms) {
        return RenderedAnswer.plain(AnswerType.OUT_OF_SCOPE, "Não consigo fazer operações por você: o Copilot só "
                + "consulta e simula. Você pode fazer isso pelas telas do app.",
                AnswerRenderer.resolve(links, pseudonyms));
    }

    /** Dados insuficientes (05-states): o que falta e a alternativa possível, sem chamar o modelo (§5). */
    public static RenderedAnswer insufficientData(Intent intent) {
        String alternative = switch (intent) {
            case GOAL_SIMULATION -> "Para simular, diga qual objetivo e o que mudar: o valor por mês, o prazo ou "
                    + "o corte de gastos em uma categoria.";
            case GOALS -> "Crie um objetivo para acompanhar o progresso.";
            case CARDS -> "Cadastre um cartão e importe a fatura para ver os dados.";
            case HEALTH, INSIGHTS, FORECAST -> "Esses dados ficam prontos depois que o app processar as suas "
                    + "transações. Tente de novo em alguns minutos.";
            default -> "Importe extratos ou registre transações para eu ter o que analisar.";
        };
        return RenderedAnswer.plain(AnswerType.INSUFFICIENT_DATA, "Ainda não tenho dados suficientes sobre "
                + subject(intent) + ". " + alternative, List.of());
    }

    static String subject(Intent intent) {
        return switch (intent) {
            case OVERVIEW -> "a sua visão geral";
            case CASH_FLOW -> "os seus gastos e receitas";
            case CATEGORY -> "as suas categorias";
            case MERCHANT -> "os seus comerciantes";
            case HEALTH -> "a sua saúde financeira";
            case INSIGHTS -> "os seus insights";
            case CARDS -> "os seus cartões";
            case FORECAST -> "a sua previsão";
            case GOALS -> "os seus objetivos";
            case GOAL_SIMULATION -> "a simulação do objetivo";
            case OUT_OF_SCOPE, UNSUPPORTED_ACTION -> "o pedido";
        };
    }
}
