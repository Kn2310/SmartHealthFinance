package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.exception.CardNotFoundException;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.application.usecase.GetCardsSummary;
import com.smarthealthfinance.cards.application.usecase.GetInstallmentPlans;
import com.smarthealthfinance.cards.application.usecase.GetInvoice;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.copilot.application.exception.ContextNotFoundException;
import com.smarthealthfinance.copilot.application.exception.TargetNotFoundException;
import com.smarthealthfinance.copilot.application.port.EntryContextResolver;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.exception.GoalNotFoundException;
import com.smarthealthfinance.goals.application.usecase.GetGoal;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.insights.application.dto.InsightViews;
import com.smarthealthfinance.insights.application.exception.InsightNotFoundException;
import com.smarthealthfinance.insights.application.usecase.InsightQueries;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contexto de entrada (ADR-0040 §3): valida o alvo no Workspace e o pseudonimiza, só por casos de uso de leitura. */
class UseCaseEntryContextResolverTest {

    private static final UUID WORKSPACE = UUID.fromString("5d0c1a52-7e1f-4a53-9c33-0a5d1f0b7c01");
    private static final UUID CARD = UUID.fromString("0b8f8a5e-4a3c-4d36-9a52-2f4a0e3c9d11");
    private static final UUID GOAL = UUID.fromString("a0f0c0de-1111-4222-8333-444455556666");
    private static final UUID INSIGHT = UUID.fromString("b1e1d1ef-1111-4222-8333-444455556666");
    private static final String NICKNAME = "Roxinho da Ana";

    private final InsightQueries insights = mock(InsightQueries.class);
    private final GetInvoice invoices = mock(GetInvoice.class);
    private final GetInstallmentPlans plans = mock(GetInstallmentPlans.class);
    private final GetGoal goals = mock(GetGoal.class);
    private final GetCardsSummary cards = mock(GetCardsSummary.class);
    private final UseCaseEntryContextResolver resolver = new UseCaseEntryContextResolver(insights, invoices, plans,
            goals, cards);

    private void activeCard() {
        CardView card = mock(CardView.class);
        when(card.id()).thenReturn(CARD);
        when(card.brand()).thenReturn(CardBrand.VISA);
        when(card.nickname()).thenReturn(NICKNAME);
        CardsSummaryView.CardSummary summary = mock(CardsSummaryView.CardSummary.class);
        when(summary.card()).thenReturn(card);
        CardsSummaryView view = mock(CardsSummaryView.class);
        when(view.cards()).thenReturn(List.of(summary));
        when(cards.execute(WORKSPACE)).thenReturn(view);
    }

    private GoalView goalView() {
        GoalView goal = mock(GoalView.class);
        when(goal.id()).thenReturn(GOAL);
        when(goal.type()).thenReturn(GoalType.PURCHASE);
        when(goal.name()).thenReturn("Viagem Sigilosa");
        when(goals.execute(WORKSPACE, GOAL)).thenReturn(goal);
        return goal;
    }

    private void insightDetail(InsightDetector detector, String periodKey, String subject) {
        Insight insight = mock(Insight.class);
        InsightContent content = mock(InsightContent.class);
        when(content.title()).thenReturn("TITULO PRONTO");
        when(insight.content()).thenReturn(content);
        when(insight.id()).thenReturn(INSIGHT);
        when(insight.detector()).thenReturn(detector);
        when(insight.periodKey()).thenReturn(periodKey);
        when(insight.subject()).thenReturn(subject);
        InsightViews.Detail detail = mock(InsightViews.Detail.class);
        when(detail.insight()).thenReturn(insight);
        when(insights.detail(WORKSPACE, INSIGHT)).thenReturn(detail);
    }

    @Test
    void verifyPassesWhenTheTargetExistsInTheWorkspace() {
        goalView();
        insightDetail(InsightDetector.CATEGORY_SPIKE, "2026-11", "FOOD");

        resolver.verify(WORKSPACE, new EntryContext.Goal(GOAL));
        resolver.verify(WORKSPACE, new EntryContext.Insight(INSIGHT));
        resolver.verify(WORKSPACE, new EntryContext.Invoice(CARD, YearMonth.of(2026, 10)));
        resolver.verify(WORKSPACE, new EntryContext.InstallmentPlan(CARD, GOAL));
    }

    @Test
    void everyNotFoundOfTheUseCasesBecomesContextNotFound() {
        when(goals.execute(WORKSPACE, GOAL)).thenThrow(new GoalNotFoundException());
        when(insights.detail(WORKSPACE, INSIGHT)).thenThrow(new InsightNotFoundException());
        when(invoices.byDueMonth(WORKSPACE, CARD, "2026-10")).thenThrow(new InvoiceNotFoundException());
        when(plans.get(WORKSPACE, CARD, GOAL)).thenThrow(new TransactionNotFoundException());
        UUID otherCard = UUID.randomUUID();
        when(invoices.byDueMonth(WORKSPACE, otherCard, "2026-10")).thenThrow(new CardNotFoundException());

        for (EntryContext context : List.of(new EntryContext.Goal(GOAL), new EntryContext.Insight(INSIGHT),
                new EntryContext.Invoice(CARD, YearMonth.of(2026, 10)),
                new EntryContext.InstallmentPlan(CARD, GOAL),
                new EntryContext.Invoice(otherCard, YearMonth.of(2026, 10)))) {
            assertThatThrownBy(() -> resolver.verify(WORKSPACE, context)).isInstanceOf(ContextNotFoundException.class);
        }
    }

    @Test
    void invoiceContextPseudonymizesTheCardFixesTheMonthAndLinksTheInvoiceScreen() {
        activeCard();
        Pseudonyms pseudonyms = new Pseudonyms();

        var resolved = resolver.resolve(WORKSPACE, new EntryContext.Invoice(CARD, YearMonth.of(2026, 10)),
                pseudonyms);

        assertThat(resolved.target(PseudonymKind.CARD)).contains("cartao_1");
        assertThat(resolved.monthValue()).contains(YearMonth.of(2026, 10));
        assertThat(resolved.screen()).isEqualTo(OfferedAction.invoice("cartao_1", "2026-10"));
        assertThat(pseudonyms.find("cartao_1")).hasValueSatisfying(entry -> {
            assertThat(entry.id()).isEqualTo(CARD.toString());
            assertThat(entry.displayName()).isEqualTo(NICKNAME);
        });
    }

    @Test
    void installmentPlanAndGoalContextsLinkTheirScreens() {
        activeCard();
        goalView();

        var plan = resolver.resolve(WORKSPACE, new EntryContext.InstallmentPlan(CARD, UUID.randomUUID()),
                new Pseudonyms());
        var goal = resolver.resolve(WORKSPACE, new EntryContext.Goal(GOAL), new Pseudonyms());

        assertThat(plan.screen()).isEqualTo(new OfferedAction(ActionKind.OPEN_INSTALLMENTS, "cartao_1"));
        assertThat(plan.monthValue()).isEmpty();
        assertThat(goal.target(PseudonymKind.GOAL)).contains("objetivo_1");
        assertThat(goal.screen()).isEqualTo(new OfferedAction(ActionKind.OPEN_GOAL, "objetivo_1"));
    }

    @Test
    void anInvoiceInsightAlsoFixesTheCardAndTheDueMonth() {
        activeCard();
        insightDetail(InsightDetector.INVOICE_ABOVE_PATTERN, "2026-11", CARD.toString());

        var resolved = resolver.resolve(WORKSPACE, new EntryContext.Insight(INSIGHT), new Pseudonyms());

        assertThat(resolved.target(PseudonymKind.INSIGHT)).contains("insight_1");
        assertThat(resolved.target(PseudonymKind.CARD)).contains("cartao_1");
        assertThat(resolved.monthValue()).contains(YearMonth.of(2026, 11));
        assertThat(resolved.screen()).isEqualTo(new OfferedAction(ActionKind.OPEN_INSIGHT, "insight_1"));
    }

    @Test
    void anotherInsightOnlyPseudonymizesItself() {
        insightDetail(InsightDetector.CATEGORY_SPIKE, "2026-11", "FOOD");

        var resolved = resolver.resolve(WORKSPACE, new EntryContext.Insight(INSIGHT), new Pseudonyms());

        assertThat(resolved.targets()).containsOnlyKeys(PseudonymKind.INSIGHT);
        assertThat(resolved.monthValue()).isEmpty();
    }

    @Test
    void aCardOutsideTheActiveCardsIsATargetNotFoundForThePipeline() {
        activeCard();

        assertThatThrownBy(() -> resolver.resolve(WORKSPACE,
                new EntryContext.Invoice(UUID.randomUUID(), YearMonth.of(2026, 10)), new Pseudonyms()))
                .isInstanceOf(TargetNotFoundException.class);
    }

    @Test
    void theResolvedContextNeverPrintsPseudonymsOrMonths() {
        activeCard();

        var resolved = resolver.resolve(WORKSPACE, new EntryContext.Invoice(CARD, YearMonth.of(2026, 10)),
                new Pseudonyms());

        assertThat(resolved.toString()).doesNotContain("cartao_1", "2026");
        EntryContextResolver.Resolved copy = resolved;
        assertThat(copy.targets()).hasSize(1);
    }
}
