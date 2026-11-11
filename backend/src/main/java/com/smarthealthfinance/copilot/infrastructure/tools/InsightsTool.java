package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import com.smarthealthfinance.insights.application.dto.InsightViews;
import com.smarthealthfinance.insights.application.usecase.InsightQueries;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightsFreshnessState;
import com.smarthealthfinance.insights.domain.model.Insight;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code INSIGHTS} (ADR-0039 §4) sobre {@link InsightQueries#list} (primeira página, até 8 insights ativos, na ordem
 * de prioridade do módulo). Cada insight vira {@code insight_N}, descrito pelo tipo do detector e pela importância;
 * entram só o impacto em dinheiro, o impacto em pontos e, para detectores de categoria ou comerciante, o nome do
 * assunto como texto. Título, apoio, linha de mudança, recomendação e evidências (textos prontos) nunca entram.
 * O caso de uso {@code highlights} não é usado: a lista já traz os mesmos insights, na mesma ordem.
 */
@Component
public class InsightsTool implements FinancialTool {

    static final String SOURCE = "insights-v1";
    static final int MAX_INSIGHTS = 8;
    static final int MAX_ACTIONS = 3;

    private final InsightQueries insights;

    public InsightsTool(InsightQueries insights) {
        this.insights = insights;
    }

    @Override
    public Intent intent() {
        return Intent.INSIGHTS;
    }

    @Override
    public Result collect(Request request) {
        Optional<UUID> target = request.decision().targetValue()
                .flatMap(value -> request.pseudonyms().find(PseudonymKind.INSIGHT, value))
                .map(entry -> UUID.fromString(entry.id()));
        if (target.isPresent()) {
            return targeted(request, target.get());
        }
        InsightViews.Page page = insights.list(request.workspaceId(), null, null, null, 0, MAX_INSIGHTS);
        if (page.freshness().state() == InsightsFreshnessState.NOT_BUILT) {
            return Result.of(Status.NOT_BUILT);
        }
        if (page.state() == InsightViews.DataState.NO_DATA || page.items().isEmpty()) {
            return Result.of(Status.NO_DATA);
        }
        return build(request.pseudonyms(), page.items().stream().limit(MAX_INSIGHTS).toList(),
                ToolValues.month(page.period().month()), page.period().partial(), page.freshness().state());
    }

    /** Com um insight alvo (contexto de entrada), só ele: o detalhe do caso de uso, que autoriza o membro. */
    private Result targeted(Request request, UUID insightId) {
        InsightViews.Detail detail = ToolSupport.lookup(() -> insights.detail(request.workspaceId(), insightId));
        if (detail.freshness().state() == InsightsFreshnessState.NOT_BUILT) {
            return Result.of(Status.NOT_BUILT);
        }
        Insight insight = detail.insight();
        return build(request.pseudonyms(), List.of(insight), insight.periodKey(), insight.partial(),
                detail.freshness().state());
    }

    private static Result build(Pseudonyms pseudonyms, List<Insight> items, String period, boolean partial,
                                InsightsFreshnessState state) {
        ToolValues.Builder values = new ToolValues.Builder(SOURCE);
        List<OfferedAction> actions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Insight insight = items.get(i);
            String pseudonym = pseudonyms.assign(PseudonymKind.INSIGHT, insight.id().toString(),
                    insight.detector().name(), insight.content().title());
            String subject = pseudonym + ", " + describe(insight.detector()) + ", "
                    + (insight.importance() == Importance.IMPORTANT ? "importante" : "informativo");
            ValueKind kind = insight.detector() == InsightDetector.BALANCE_MAY_GO_NEGATIVE ? ValueKind.ESTIMATED
                    : ValueKind.FACT;
            boolean impact = insight.impactAmount() != null || insight.impactPoints() != null;
            boolean named = hasNamedSubject(insight.detector()) && insight.content().subjectLabel() != null;
            if (!impact && !named) {
                values.text("Insight " + subject, describe(insight.detector()), period);
            }
            values.money("Impacto financeiro de " + subject, insight.impactAmount(), kind, period)
                    .score("Impacto em pontos de " + subject, insight.impactPoints(), kind, period);
            if (named) {
                values.text("Assunto de " + subject, insight.content().subjectLabel(), period);
            }
            if (i < MAX_ACTIONS) {
                actions.add(new OfferedAction(ActionKind.OPEN_INSIGHT, pseudonym));
            }
        }
        DataQuality quality = new DataQuality(null, partial, List.of(), freshness(state), Set.of());
        return new Result(Status.DATA, values.build(), quality, List.of(new SourceRef("InsightQueries", SOURCE)),
                actions);
    }

    private static DataFreshness freshness(InsightsFreshnessState state) {
        return switch (state) {
            case UP_TO_DATE -> DataFreshness.UP_TO_DATE;
            case UPDATING -> DataFreshness.UPDATING;
            case NOT_BUILT -> DataFreshness.NOT_BUILT;
        };
    }

    /** Só estes detectores têm como assunto uma categoria do catálogo ou um comerciante normalizado. */
    private static boolean hasNamedSubject(InsightDetector detector) {
        return detector == InsightDetector.CATEGORY_SPIKE || detector == InsightDetector.UNUSUAL_EXPENSE
                || detector == InsightDetector.NEW_RECURRING_MERCHANT;
    }

    static String describe(InsightDetector detector) {
        return switch (detector) {
            case CATEGORY_SPIKE -> "alta de gasto em uma categoria";
            case SPENDING_ABOVE_PATTERN -> "gasto total acima do padrão";
            case NEW_RECURRING_MERCHANT -> "nova cobrança recorrente de comerciante";
            case UNUSUAL_EXPENSE -> "despesa fora do comum";
            case RECURRING_INCOME_MISSING -> "receita recorrente ausente";
            case RECURRING_INCOME_LOWER -> "receita recorrente abaixo do esperado";
            case NEW_INCOME_SOURCE -> "nova fonte de receita";
            case HEALTH_SCORE_CHANGE -> "mudança no score de saúde financeira";
            case HEALTH_DIMENSION_CHANGE -> "mudança em uma dimensão da saúde financeira";
            case LOW_RESERVE -> "reserva baixa";
            case UNCATEGORIZED_SHARE -> "gasto sem categoria elevado";
            case INVOICE_ABOVE_PATTERN -> "fatura de cartão acima do padrão";
            case CARD_UTILIZATION_HIGH -> "utilização alta do limite do cartão";
            case INVOICE_DUE -> "fatura de cartão perto do vencimento";
            case BALANCE_MAY_GO_NEGATIVE -> "saldo previsto para ficar negativo";
        };
    }
}
