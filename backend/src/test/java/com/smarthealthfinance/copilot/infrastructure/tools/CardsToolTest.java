package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.dto.FutureInvoicesView;
import com.smarthealthfinance.cards.application.dto.InstallmentPlanView;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.usecase.GetCardsSummary;
import com.smarthealthfinance.cards.application.usecase.GetFutureInvoices;
import com.smarthealthfinance.cards.application.usecase.GetInstallmentPlans;
import com.smarthealthfinance.cards.application.usecase.GetInvoice;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.enums.InstallmentPlanStatus;
import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import org.junit.jupiter.api.Test;
import com.smarthealthfinance.cards.application.dto.InvoiceView;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.copilot.application.exception.TargetNotFoundException;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.insights.application.dto.InsightViews;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.InsightsFreshnessState;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.application.usecase.InsightQueries;
import java.math.BigDecimal;
import java.util.Map;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.WORKSPACE;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.assertNoLeak;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.brl;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.decision;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.request;
import static com.smarthealthfinance.copilot.infrastructure.tools.ToolTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardsToolTest {

    private static final String NICKNAME = "Roxinho da Ana";
    private static final String LAST4 = "4321";
    private static final String INSTITUTION = "Banco Fictício";
    private static final UUID CARD_ONE = UUID.fromString("0b8f8a5e-4a3c-4d36-9a52-2f4a0e3c9d11");
    private static final UUID CARD_TWO = UUID.fromString("7c1e2d3f-5b6a-4c7d-8e9f-0a1b2c3d4e5f");

    private final GetCardsSummary summary = mock(GetCardsSummary.class);
    private final GetFutureInvoices future = mock(GetFutureInvoices.class);
    private final GetInstallmentPlans plans = mock(GetInstallmentPlans.class);
    private final GetInvoice invoices = mock(GetInvoice.class);
    private final InsightQueries insights = mock(InsightQueries.class);
    private final CardsTool tool = new CardsTool(summary, future, plans, invoices, insights);

    private static CardsSummaryView.CardSummary card(UUID id, CardBrand brand, String nickname, String amount) {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        var view = new CardView(id, WORKSPACE, nickname, INSTITUTION, brand, LAST4, brl("5000"), 5, 12, null,
                CardStatus.ACTIVE, YearMonth.of(2026, 1), now, now);
        var invoice = new InvoiceSummaryView(YearMonth.of(2026, 11), LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12),
                InvoiceDatesSource.CALCULATED, InvoiceStatus.OPEN, brl(amount), brl("0"), brl(amount), brl("0"),
                brl(amount), null, true);
        var utilization = new UtilizationView(brl("5000"), brl("1800"), brl("3200"), brl("0"), 36, 20,
                new UtilizationView.Composition(brl("0"), brl(amount), brl("600")), LocalDate.of(2026, 11, 10),
                "cards-v1");
        return new CardsSummaryView.CardSummary(view, invoice, utilization, brl("450"));
    }

    private static CardsSummaryView view(CardsSummaryView.CardSummary... cards) {
        return new CardsSummaryView(brl("10000"), brl("3000"), brl("1200"), LocalDate.of(2026, 11, 12),
                List.of(cards), LocalDate.of(2026, 11, 10), "cards-v1");
    }

    @Test
    void summarizesCardsWithPseudonymsOnly() {
        when(summary.execute(WORKSPACE)).thenReturn(view(card(CARD_ONE, CardBrand.VISA, NICKNAME, "1200"),
                card(CARD_TWO, CardBrand.ELO, "Cartão do trabalho", "800")));
        Pseudonyms pseudonyms = new Pseudonyms();

        var result = tool.collect(request(IntentDecision.of(Intent.CARDS), pseudonyms));

        assertThat(result.status()).isEqualTo(FinancialTool.Status.DATA);
        assertThat(value(result, "Limite total dos cartões ativos").value()).isEqualTo("10000");
        assertThat(value(result, "Total a pagar nas faturas abertas").value()).isEqualTo("1200");
        assertThat(value(result, "Próximo vencimento de fatura com valor em aberto").value())
                .isEqualTo("2026-11-12");
        assertThat(value(result, "Total da fatura atual de cartao_1 (VISA)").value()).isEqualTo("1200");
        assertThat(value(result, "Total da fatura atual de cartao_2 (ELO)").value()).isEqualTo("800");
        assertThat(value(result, "Situação da fatura atual de cartao_1 (VISA)").value()).isEqualTo("aberta");
        assertThat(value(result, "Utilização do limite de cartao_1 (VISA)").value()).isEqualTo("36");
        assertThat(value(result, "Valor já comprometido na próxima fatura de cartao_1 (VISA)").value())
                .isEqualTo("450");
        assertThat(result.quality().partialPeriod()).isTrue();
        assertThat(result.actions()).contains(OfferedAction.invoice("cartao_1", "2026-11"),
                new OfferedAction(ActionKind.OPEN_INSTALLMENTS, "cartao_2"));
        verify(future, never()).execute(any(), any());
        verify(plans, never()).list(any(), any(), any());
        assertNoLeak(result, NICKNAME, "Cartão do trabalho", LAST4, INSTITUTION, CARD_ONE.toString(),
                CARD_TWO.toString());
    }

    @Test
    void detailsOnlyTheTargetCardWithFutureInvoicesAndInstallments() {
        when(summary.execute(WORKSPACE)).thenReturn(view(card(CARD_ONE, CardBrand.VISA, NICKNAME, "1200"),
                card(CARD_TWO, CardBrand.ELO, "Cartão do trabalho", "800")));
        when(future.execute(WORKSPACE, CARD_TWO)).thenReturn(new FutureInvoicesView(List.of(
                new FutureInvoicesView.Month(YearMonth.of(2026, 12), brl("300"), List.of()),
                new FutureInvoicesView.Month(YearMonth.of(2027, 1), brl("200"), List.of()),
                new FutureInvoicesView.Month(YearMonth.of(2027, 2), brl("100"), List.of()),
                new FutureInvoicesView.Month(YearMonth.of(2027, 3), brl("50"), List.of())), brl("650"),
                LocalDate.of(2026, 11, 10), "cards-v1"));
        when(plans.list(WORKSPACE, CARD_TWO, "ACTIVE")).thenReturn(List.of(
                plan(brl("1200"), brl("400")), plan(brl("600"), brl("100"))));
        when(insights.list(WORKSPACE, null, "CARDS", null, 0, 50)).thenReturn(insightPage());
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.CARD, CARD_ONE.toString(), "VISA", NICKNAME);
        pseudonyms.assign(PseudonymKind.CARD, CARD_TWO.toString(), "ELO", "Cartão do trabalho");

        var result = tool.collect(request(decision(Intent.CARDS, null, null, "cartao_2", null), pseudonyms));

        assertThat(result.values()).noneMatch(v -> v.label().contains("cartao_1"));
        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Valor comprometido em parcelas na fatura"))
                .extracting(v -> v.period()).containsExactly("2026-12", "2027-01", "2027-02");
        assertThat(value(result, "Parcelamentos ativos de cartao_2 (ELO)").value()).isEqualTo("2");
        assertThat(value(result, "Saldo restante dos parcelamentos ativos de cartao_2 (ELO)").value())
                .isEqualTo("1300");
        assertThat(result.actions()).containsExactly(OfferedAction.invoice("cartao_2", "2026-11"),
                new OfferedAction(ActionKind.OPEN_INSTALLMENTS, "cartao_2"));
        assertNoLeak(result, NICKNAME, "Cartão do trabalho", LAST4, INSTITUTION, CARD_ONE.toString(),
                CARD_TWO.toString());
    }

    private static InstallmentPlanView plan(com.smarthealthfinance.shared.domain.Money total,
                                            com.smarthealthfinance.shared.domain.Money paid) {
        return new InstallmentPlanView(UUID.randomUUID(), "COMPRA SECRETA", LocalDate.of(2026, 8, 3), total, 6,
                brl("200"), 2, paid, 3, brl("400"), YearMonth.of(2027, 1), InstallmentPlanStatus.ACTIVE, List.of(),
                List.of(), null);
    }

    @Test
    void limitsToFiveCards() {
        var cards = new CardsSummaryView.CardSummary[7];
        for (int i = 0; i < cards.length; i++) {
            cards[i] = card(UUID.randomUUID(), CardBrand.VISA, "Apelido", "100");
        }
        when(summary.execute(WORKSPACE)).thenReturn(view(cards));

        var result = tool.collect(request(Intent.CARDS));

        assertThat(ToolTestSupport.valuesWithLabelStarting(result, "Total da fatura atual de ")).hasSize(5);
    }

    @Test
    void reportsNoDataWithoutActiveCards() {
        when(summary.execute(WORKSPACE)).thenReturn(view());

        assertThat(tool.collect(request(Intent.CARDS)).status()).isEqualTo(FinancialTool.Status.NO_DATA);
    }

    private static Insight invoiceInsight(UUID cardId, String periodKey) {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        var evidence = new InsightEvidence(
                List.of(new KeyFigure("difference", "Diferença", FigureKind.MONEY, new BigDecimal("520.60"),
                        LocalDate.of(2026, 7, 6), LocalDate.of(2026, 11, 9))),
                List.of(), List.of(
                        new Contributor(ContributorKind.FACTOR, "category:FOOD", "Alimentação fora",
                                new BigDecimal("311.00"), null, null, null),
                        new Contributor(ContributorKind.FACTOR, "plan:abc", "Parcela de Loja Tech (3/10)",
                                new BigDecimal("160.00"), null, null, null),
                        new Contributor(ContributorKind.FACTOR, "others", "Outros", new BigDecimal("49.60"), null,
                                null, null)),
                "comparacao", List.of(), List.of());
        var content = new InsightContent("TITULO", "APOIO", NICKNAME, null, null, evidence,
                List.of(InsightAction.primary(com.smarthealthfinance.insights.domain.enums.ActionKind.VIEW_INVOICE,
                        "Ver", Map.of())), InsightRefs.none());
        return new Insight(UUID.randomUUID(), WORKSPACE, "fp", "insights-v3", InsightDetector.INVOICE_ABOVE_PATTERN,
                periodKey, cardId.toString(), InsightStatus.ACTIVE, null, Importance.IMPORTANT, 50, 50,
                Confidence.HIGH, 10, brl("520.60"), null, true, content, null, null, null, null, 1, now, now, now);
    }

    private static InsightViews.Page insightPage(Insight... items) {
        return new InsightViews.Page(InsightViews.DataState.READY,
                new InsightsFreshness(InsightsFreshnessState.UP_TO_DATE, null, null),
                new InsightViews.Period(YearMonth.of(2026, 11), true),
                new InsightStore.ChipCounts(items.length, 0, 0, 0, 0, 0), List.of(items), 0, 50, items.length);
    }

    private void stubTargetCard() {
        when(summary.execute(WORKSPACE)).thenReturn(view(card(CARD_TWO, CardBrand.ELO, "Cartão do trabalho", "800")));
        when(future.execute(WORKSPACE, CARD_TWO)).thenReturn(new FutureInvoicesView(List.of(), brl("0"),
                LocalDate.of(2026, 11, 10), "cards-v1"));
        when(plans.list(WORKSPACE, CARD_TWO, "ACTIVE")).thenReturn(List.of());
    }

    private static Pseudonyms targetPseudonyms() {
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.CARD, CARD_TWO.toString(), "ELO", "Cartão do trabalho");
        return pseudonyms;
    }

    @Test
    void invoiceFactorsOfTheActiveInsightEnterTheContextWithoutMerchantNamesAndFeedTheBreakdown() {
        stubTargetCard();
        when(insights.list(WORKSPACE, null, "CARDS", null, 0, 50))
                .thenReturn(insightPage(invoiceInsight(UUID.randomUUID(), "2026-11"),
                        invoiceInsight(CARD_TWO, "2026-11")));

        var result = tool.collect(request(decision(Intent.CARDS, YearMonth.of(2026, 11), null, "cartao_1", null),
                targetPseudonyms()));

        String tag = "cartao_1 (ELO)";
        assertThat(value(result, "Diferença da fatura de " + tag + " contra a média das anteriores").value())
                .isEqualTo("520.6");
        assertThat(value(result, "Maior fator da diferença da fatura de " + tag).value()).isEqualTo("311");
        assertThat(value(result, "Segundo maior fator da diferença da fatura de " + tag).value()).isEqualTo("160");
        assertThat(value(result, "Demais fatores da diferença da fatura de " + tag).value()).isEqualTo("49.6");
        assertNoLeak(result, "Loja Tech", "Alimentação fora", NICKNAME);
        assertThat(result.breakdown()).isNotNull();
        assertThat(result.breakdown().rows()).extracting(row -> row.label())
                .containsExactly("Alimentação fora", "Parcela de Loja Tech (3/10)", "Outros");
        assertThat(result.values().get(result.breakdown().totalIndex()).label()).startsWith("Diferença da fatura");
        assertThat(result.quality().partialPeriod()).isTrue();
    }

    @Test
    void breakdownIsAbsentWithoutAnActiveInsightForThatInvoice() {
        stubTargetCard();
        when(insights.list(WORKSPACE, null, "CARDS", null, 0, 50))
                .thenReturn(insightPage(invoiceInsight(CARD_TWO, "2026-10")));

        var result = tool.collect(request(decision(Intent.CARDS, null, null, "cartao_1", null),
                targetPseudonyms()));

        assertThat(result.breakdown()).isNull();
        assertThat(result.values()).noneMatch(v -> v.label().contains("fator"));
    }

    @Test
    void askedInvoiceMonthAddsThatInvoiceAndSetsTheActionMonth() {
        stubTargetCard();
        when(insights.list(WORKSPACE, null, "CARDS", null, 0, 50)).thenReturn(insightPage());
        var october = new InvoiceSummaryView(YearMonth.of(2026, 10), LocalDate.of(2026, 9, 6),
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12),
                InvoiceDatesSource.CALCULATED, InvoiceStatus.PAID, brl("2100"), brl("0"), brl("2100"), brl("2100"),
                brl("0"), null, false);
        when(invoices.byDueMonth(WORKSPACE, CARD_TWO, "2026-10")).thenReturn(new InvoiceView(october, 12,
                LocalDate.of(2026, 11, 10), "cards-v1"));

        var result = tool.collect(request(decision(Intent.CARDS, YearMonth.of(2026, 10), null, "cartao_1", null),
                targetPseudonyms()));

        assertThat(value(result, "Total da fatura pedida de cartao_1 (ELO)").value()).isEqualTo("2100");
        assertThat(value(result, "Total da fatura pedida de cartao_1 (ELO)").period()).isEqualTo("2026-10");
        assertThat(result.actions()).contains(OfferedAction.invoice("cartao_1", "2026-10"));
    }

    @Test
    void invoiceThatDoesNotExistBecomesATargetNotFound() {
        when(summary.execute(WORKSPACE)).thenReturn(view(card(CARD_TWO, CardBrand.ELO, "Cartão do trabalho", "800")));
        when(invoices.byDueMonth(WORKSPACE, CARD_TWO, "2026-03")).thenThrow(new InvoiceNotFoundException());

        assertThatThrownBy(() -> tool.collect(request(decision(Intent.CARDS, YearMonth.of(2026, 3), null,
                "cartao_1", null), targetPseudonyms()))).isInstanceOf(TargetNotFoundException.class);
    }
}
