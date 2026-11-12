package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.dto.FutureInvoicesView;
import com.smarthealthfinance.cards.application.dto.InstallmentPlanView;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.usecase.GetCardsSummary;
import com.smarthealthfinance.cards.application.usecase.GetFutureInvoices;
import com.smarthealthfinance.cards.application.usecase.GetInstallmentPlans;
import com.smarthealthfinance.cards.application.usecase.GetInvoice;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
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
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code CARDS} (ADR-0039 §4) sobre {@link GetCardsSummary}: limite, utilizado, faturas abertas e próximo vencimento
 * e, por cartão ({@code cartao_N} com a bandeira; até 5, ou só o alvo da pergunta), a fatura atual, a utilização e o
 * comprometido na próxima fatura. Com um cartão alvo, soma as próximas 3 faturas ({@link GetFutureInvoices}) e os
 * parcelamentos ativos ({@link GetInstallmentPlans}, filtro {@code ACTIVE}). Apelido, final e instituição nunca
 * entram. Utilização em % inteiro, como o caso de uso devolve.
 *
 * <p>Com alvo e mês (contexto de entrada ou mês citado), também a fatura daquele vencimento ({@link GetInvoice}). Com
 * insight ativo {@code INVOICE_ABOVE_PATTERN} da fatura em foco ({@link InsightQueries#list}), os fatores do D11
 * (ADR-0028 §2) entram no contexto com rótulo sem dígitos e valor; o rótulo de exibição de cada fator (com
 * comerciante e parcela) só vai ao quadro da tela, nunca ao modelo (ADR-0040 §3).
 */
@Component
public class CardsTool implements FinancialTool {

    static final String SOURCE = "cards-v1";
    static final int MAX_CARDS = 5;
    static final int MAX_FUTURE_INVOICES = 3;
    static final String ACTIVE_PLANS = "ACTIVE";
    static final String CARDS_TYPE = "CARDS";
    static final int INSIGHT_PAGE_SIZE = 50;

    private final GetCardsSummary summary;
    private final GetFutureInvoices futureInvoices;
    private final GetInstallmentPlans installmentPlans;
    private final GetInvoice invoices;
    private final InsightQueries insights;

    public CardsTool(GetCardsSummary summary, GetFutureInvoices futureInvoices, GetInstallmentPlans installmentPlans,
                     GetInvoice invoices, InsightQueries insights) {
        this.summary = summary;
        this.futureInvoices = futureInvoices;
        this.installmentPlans = installmentPlans;
        this.invoices = invoices;
        this.insights = insights;
    }

    @Override
    public Intent intent() {
        return Intent.CARDS;
    }

    @Override
    public Result collect(Request request) {
        CardsSummaryView view = summary.execute(request.workspaceId());
        if (view.cards().isEmpty()) {
            return Result.of(Status.NO_DATA);
        }
        Optional<String> target = request.decision().targetValue()
                .filter(value -> request.pseudonyms().find(PseudonymKind.CARD, value).isPresent());
        ToolValues.Builder values = new ToolValues.Builder(SOURCE)
                .money("Limite total dos cartões ativos", view.totalLimit(), null)
                .money("Limite utilizado nos cartões ativos", view.utilized(), null)
                .money("Total a pagar nas faturas abertas", view.openInvoices(), null)
                .date("Próximo vencimento de fatura com valor em aberto", view.nextDueDate(), ValueKind.FACT);

        List<OfferedAction> actions = new ArrayList<>();
        BreakdownDraft breakdown = null;
        boolean partial = false;
        int shown = 0;
        for (CardsSummaryView.CardSummary card : view.cards()) {
            String pseudonym = ToolValues.card(request.pseudonyms(), card.card());
            if (target.isPresent() ? !target.get().equals(pseudonym) : shown >= MAX_CARDS) {
                continue;
            }
            shown++;
            String tag = pseudonym + " (" + card.card().brand() + ")";
            partial |= invoice(values, tag, "atual", card.currentInvoice());
            utilization(values, tag, card.utilization());
            values.money("Valor já comprometido na próxima fatura de " + tag, card.nextInvoiceCommitted(), null);
            YearMonth current = card.currentInvoice() == null ? null : card.currentInvoice().dueMonth();
            YearMonth focus = current;
            if (target.isPresent()) {
                Optional<YearMonth> asked = request.decision().monthValue();
                if (asked.isPresent()) {
                    focus = asked.get();
                    if (!focus.equals(current)) {
                        UUID cardId = card.card().id();
                        InvoiceSummaryView specific = ToolSupport.lookup(() -> invoices.byDueMonth(
                                request.workspaceId(), cardId, asked.get().toString())).invoice();
                        partial |= invoice(values, tag, "pedida", specific);
                    }
                }
            }
            actions.add(OfferedAction.invoice(pseudonym, focus == null ? null : focus.toString()));
            actions.add(new OfferedAction(ActionKind.OPEN_INSTALLMENTS, pseudonym));
            if (target.isPresent()) {
                details(request, values, tag, card.card().id());
                Optional<FactorBreakdown> factors = factors(request, values, tag, card.card().id(), focus,
                        card.currentInvoice());
                if (factors.isPresent()) {
                    breakdown = factors.get().draft();
                    partial |= factors.get().partial();
                }
            }
        }
        if (shown == 0) {
            return Result.of(Status.NO_DATA);
        }
        DataQuality quality = new DataQuality(null, partial, List.of(), DataFreshness.UP_TO_DATE, Set.of());
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("GetCardsSummary", view.calculationVersion())), actions, breakdown);
    }

    /** @return a fatura ainda está aberta (valor parcial) */
    private static boolean invoice(ToolValues.Builder values, String tag, String which, InvoiceSummaryView invoice) {
        if (invoice == null) {
            return false;
        }
        String due = ToolValues.month(invoice.dueMonth());
        values.money("Total da fatura " + which + " de " + tag, invoice.amount(), due)
                .money("Falta pagar da fatura " + which + " de " + tag, invoice.remaining(), due)
                .date("Vencimento da fatura " + which + " de " + tag, invoice.effectiveDueDate(), ValueKind.FACT)
                .text("Situação da fatura " + which + " de " + tag, statusText(invoice.status()), due);
        return invoice.partial();
    }

    private static void utilization(ToolValues.Builder values, String tag, UtilizationView utilization) {
        if (utilization == null) {
            return;
        }
        values.percent("Utilização do limite de " + tag, BigDecimal.valueOf(utilization.utilizationPercent()), null)
                .money("Limite disponível de " + tag, utilization.available(), null);
    }

    private void details(Request request, ToolValues.Builder values, String tag, UUID cardId) {
        FutureInvoicesView future = ToolSupport.lookup(() -> futureInvoices.execute(request.workspaceId(), cardId));
        future.months().stream().limit(MAX_FUTURE_INVOICES).forEach(month -> values.money(
                "Valor comprometido em parcelas na fatura futura de " + tag, month.committed(),
                ToolValues.month(month.dueMonth())));
        List<InstallmentPlanView> plans = ToolSupport.lookup(
                () -> installmentPlans.list(request.workspaceId(), cardId, ACTIVE_PLANS));
        values.count("Parcelamentos ativos de " + tag, plans.size(), null);
        if (!plans.isEmpty()) {
            Money remaining = plans.stream().map(plan -> plan.total().minus(plan.paidAmount()))
                    .reduce(Money::plus).orElseThrow();
            values.money("Saldo restante dos parcelamentos ativos de " + tag, remaining, null);
        }
    }

    /** O quadro dos fatores e se o insight olha uma fatura ainda aberta. */
    private record FactorBreakdown(BreakdownDraft draft, boolean partial) {
    }

    /**
     * Fatores do D11 da fatura em foco, quando há insight ativo: a diferença contra a média e cada fator entram como
     * valores com rótulo sem dígitos; o quadro leva o nome de cada fator, e o total é a diferença (Σ fatores = Δ,
     * ADR-0028 §2).
     */
    private Optional<FactorBreakdown> factors(Request request, ToolValues.Builder values, String tag, UUID cardId,
                                              YearMonth focus, InvoiceSummaryView current) {
        if (focus == null) {
            return Optional.empty();
        }
        Optional<Insight> insight = activeInvoiceInsight(request.workspaceId(), cardId, focus);
        if (insight.isEmpty()) {
            return Optional.empty();
        }
        Optional<KeyFigure> difference = insight.get().content().evidence().figures().stream()
                .filter(figure -> "difference".equals(figure.code())).findFirst();
        List<Contributor> factors = insight.get().content().evidence().contributors().stream()
                .filter(contributor -> contributor.kind() == ContributorKind.FACTOR).toList();
        if (difference.isEmpty() || factors.isEmpty()) {
            return Optional.empty();
        }
        Money zero = current != null ? Money.zero(current.amount().currency())
                : Money.zero(insight.get().impactAmount().currency());
        String period = ToolValues.month(focus);
        int total = values.size();
        values.money("Diferença da fatura de " + tag + " contra a média das anteriores",
                new Money(difference.get().value(), zero.currency()), period);
        List<BreakdownDraft.Row> rows = new ArrayList<>();
        for (int i = 0; i < factors.size(); i++) {
            Contributor factor = factors.get(i);
            rows.add(new BreakdownDraft.Row(factor.name(), values.size(), null));
            values.money(factorLabel(factor, i, tag), new Money(factor.amount(), zero.currency()), period);
        }
        return Optional.of(new FactorBreakdown(new BreakdownDraft("Fatores da diferença da fatura", rows, total),
                insight.get().partial()));
    }

    private Optional<Insight> activeInvoiceInsight(UUID workspaceId, UUID cardId, YearMonth focus) {
        InsightViews.Page page = ToolSupport.lookup(
                () -> insights.list(workspaceId, null, CARDS_TYPE, null, 0, INSIGHT_PAGE_SIZE));
        return page.items().stream()
                .filter(item -> item.detector() == InsightDetector.INVOICE_ABOVE_PATTERN
                        && cardId.toString().equals(item.subject()) && focus.toString().equals(item.periodKey()))
                .findFirst();
    }

    /** Rótulo sem dígitos e sem comerciante: "Maior fator", "Segundo maior fator"…, "Demais fatores". */
    private static String factorLabel(Contributor factor, int index, String tag) {
        String ordinal = index == 0 ? "Maior" : capitalize(ToolSupport.ordinal(index)) + " maior";
        return ("others".equals(factor.key()) ? "Demais fatores" : ordinal + " fator")
                + " da diferença da fatura de " + tag;
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String statusText(InvoiceStatus status) {
        return switch (status) {
            case FUTURE -> "futura";
            case OPEN -> "aberta";
            case CLOSED -> "fechada";
            case PARTIALLY_PAID -> "parcialmente paga";
            case PAID -> "paga";
            case OVERDUE -> "vencida";
        };
    }
}
