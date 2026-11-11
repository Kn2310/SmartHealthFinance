package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.usecase.GetCardsSummary;
import com.smarthealthfinance.cards.application.usecase.GetInstallmentPlans;
import com.smarthealthfinance.cards.application.usecase.GetInvoice;
import com.smarthealthfinance.copilot.application.exception.ContextNotFoundException;
import com.smarthealthfinance.copilot.application.exception.TargetNotFoundException;
import com.smarthealthfinance.copilot.application.port.EntryContextResolver;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.usecase.GetGoal;
import com.smarthealthfinance.insights.application.dto.InsightViews;
import com.smarthealthfinance.insights.application.usecase.InsightQueries;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.model.Insight;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Contexto de entrada (ADR-0040 §3) pelos casos de uso de leitura da lista fechada: {@link InsightQueries#detail},
 * {@link GetInvoice}, {@link GetInstallmentPlans}, {@link GetGoal} e {@link GetCardsSummary}. Todos autorizam o
 * membro e respondem "não encontrado" para o que é de outro Workspace.
 */
@Component
public class UseCaseEntryContextResolver implements EntryContextResolver {

    private final InsightQueries insights;
    private final GetInvoice invoices;
    private final GetInstallmentPlans plans;
    private final GetGoal goals;
    private final GetCardsSummary cards;

    public UseCaseEntryContextResolver(InsightQueries insights, GetInvoice invoices, GetInstallmentPlans plans,
                                       GetGoal goals, GetCardsSummary cards) {
        this.insights = insights;
        this.invoices = invoices;
        this.plans = plans;
        this.goals = goals;
        this.cards = cards;
    }

    @Override
    public void verify(UUID workspaceId, EntryContext context) {
        try {
            switch (context) {
                case EntryContext.Insight insight -> ToolSupport.lookup(
                        () -> insights.detail(workspaceId, insight.insightId()));
                case EntryContext.Invoice invoice -> ToolSupport.lookup(
                        () -> invoices.byDueMonth(workspaceId, invoice.cardId(), invoice.month().toString()));
                case EntryContext.InstallmentPlan plan -> ToolSupport.lookup(
                        () -> plans.get(workspaceId, plan.cardId(), plan.planId()));
                case EntryContext.Goal goal -> ToolSupport.lookup(() -> goals.execute(workspaceId, goal.goalId()));
            }
        } catch (TargetNotFoundException e) {
            throw new ContextNotFoundException();
        }
    }

    @Override
    public Resolved resolve(UUID workspaceId, EntryContext context, Pseudonyms pseudonyms) {
        return switch (context) {
            case EntryContext.Insight insight -> insight(workspaceId, insight, pseudonyms);
            case EntryContext.Invoice invoice -> {
                String card = card(workspaceId, invoice.cardId(), pseudonyms);
                yield new Resolved(Map.of(PseudonymKind.CARD, card), invoice.month(),
                        OfferedAction.invoice(card, invoice.month().toString()));
            }
            case EntryContext.InstallmentPlan plan -> {
                String card = card(workspaceId, plan.cardId(), pseudonyms);
                yield new Resolved(Map.of(PseudonymKind.CARD, card), null,
                        new OfferedAction(ActionKind.OPEN_INSTALLMENTS, card));
            }
            case EntryContext.Goal goal -> {
                GoalView view = ToolSupport.lookup(() -> goals.execute(workspaceId, goal.goalId()));
                String pseudonym = ToolValues.goal(pseudonyms, view);
                yield new Resolved(Map.of(PseudonymKind.GOAL, pseudonym), null,
                        new OfferedAction(ActionKind.OPEN_GOAL, pseudonym));
            }
        };
    }

    /**
     * O insight vira {@code insight_N}; o de fatura acima do padrão também fixa o cartão e o mês de vencimento, para
     * uma pergunta sobre cartões usar a fatura do insight.
     */
    private Resolved insight(UUID workspaceId, EntryContext.Insight context, Pseudonyms pseudonyms) {
        InsightViews.Detail detail = ToolSupport.lookup(() -> insights.detail(workspaceId, context.insightId()));
        Insight insight = detail.insight();
        String pseudonym = pseudonyms.assign(PseudonymKind.INSIGHT, insight.id().toString(),
                insight.detector().name(), insight.content().title());
        Map<PseudonymKind, String> targets = new EnumMap<>(PseudonymKind.class);
        targets.put(PseudonymKind.INSIGHT, pseudonym);
        YearMonth month = null;
        if (insight.detector() == InsightDetector.INVOICE_ABOVE_PATTERN && insight.periodKey() != null) {
            month = YearMonth.parse(insight.periodKey());
            targets.put(PseudonymKind.CARD, card(workspaceId, UUID.fromString(insight.subject()), pseudonyms));
        }
        return new Resolved(targets, month, new OfferedAction(ActionKind.OPEN_INSIGHT, pseudonym));
    }

    /** Pseudônimo do cartão; fora dos cartões ativos (ex.: arquivado), a tool de cartões não o cobre. */
    private String card(UUID workspaceId, UUID cardId, Pseudonyms pseudonyms) {
        CardsSummaryView summary = cards.execute(workspaceId);
        for (CardsSummaryView.CardSummary item : summary.cards()) {
            CardView card = item.card();
            if (card.id().equals(cardId)) {
                return ToolValues.card(pseudonyms, card);
            }
        }
        throw new TargetNotFoundException();
    }
}
