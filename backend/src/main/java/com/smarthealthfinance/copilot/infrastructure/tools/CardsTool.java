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
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 */
@Component
public class CardsTool implements FinancialTool {

    static final String SOURCE = "cards-v1";
    static final int MAX_CARDS = 5;
    static final int MAX_FUTURE_INVOICES = 3;
    static final String ACTIVE_PLANS = "ACTIVE";

    private final GetCardsSummary summary;
    private final GetFutureInvoices futureInvoices;
    private final GetInstallmentPlans installmentPlans;

    public CardsTool(GetCardsSummary summary, GetFutureInvoices futureInvoices,
                     GetInstallmentPlans installmentPlans) {
        this.summary = summary;
        this.futureInvoices = futureInvoices;
        this.installmentPlans = installmentPlans;
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
        boolean partial = false;
        int shown = 0;
        for (CardsSummaryView.CardSummary card : view.cards()) {
            String pseudonym = ToolValues.card(request.pseudonyms(), card.card());
            if (target.isPresent() ? !target.get().equals(pseudonym) : shown >= MAX_CARDS) {
                continue;
            }
            shown++;
            String tag = pseudonym + " (" + card.card().brand() + ")";
            partial |= invoice(values, tag, card.currentInvoice());
            utilization(values, tag, card.utilization());
            values.money("Valor já comprometido na próxima fatura de " + tag, card.nextInvoiceCommitted(), null);
            actions.add(new OfferedAction(ActionKind.OPEN_INVOICE, pseudonym));
            actions.add(new OfferedAction(ActionKind.OPEN_INSTALLMENTS, pseudonym));
            if (target.isPresent()) {
                details(request, values, tag, card.card().id());
            }
        }
        if (shown == 0) {
            return Result.of(Status.NO_DATA);
        }
        DataQuality quality = new DataQuality(null, partial, List.of(), DataFreshness.UP_TO_DATE, Set.of());
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("GetCardsSummary", view.calculationVersion())), actions);
    }

    /** @return a fatura ainda está aberta (valor parcial) */
    private static boolean invoice(ToolValues.Builder values, String tag, InvoiceSummaryView invoice) {
        if (invoice == null) {
            return false;
        }
        String due = ToolValues.month(invoice.dueMonth());
        values.money("Total da fatura atual de " + tag, invoice.amount(), due)
                .money("Falta pagar da fatura atual de " + tag, invoice.remaining(), due)
                .date("Vencimento da fatura atual de " + tag, invoice.effectiveDueDate(), ValueKind.FACT)
                .text("Situação da fatura atual de " + tag, statusText(invoice.status()), due);
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
        FutureInvoicesView future = futureInvoices.execute(request.workspaceId(), cardId);
        future.months().stream().limit(MAX_FUTURE_INVOICES).forEach(month -> values.money(
                "Valor comprometido em parcelas na fatura futura de " + tag, month.committed(),
                ToolValues.month(month.dueMonth())));
        List<InstallmentPlanView> plans = installmentPlans.list(request.workspaceId(), cardId, ACTIVE_PLANS);
        values.count("Parcelamentos ativos de " + tag, plans.size(), null);
        if (!plans.isEmpty()) {
            Money remaining = plans.stream().map(plan -> plan.total().minus(plan.paidAmount()))
                    .reduce(Money::plus).orElseThrow();
            values.money("Saldo restante dos parcelamentos ativos de " + tag, remaining, null);
        }
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
