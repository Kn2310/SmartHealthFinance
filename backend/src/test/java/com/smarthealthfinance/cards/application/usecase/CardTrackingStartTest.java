package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.InstallmentPlanView;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.domain.enums.InstallmentPlanStatus;
import com.smarthealthfinance.cards.domain.enums.InstallmentStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static com.smarthealthfinance.cards.application.CardsTestContext.BRL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Início do acompanhamento (ADR-0025 §15) com o Aurora de 09-dados-de-exemplo: o cartão é cadastrado em 22 set e o
 * notebook de 12 jul é lançado depois, em 10×. As parcelas 1–2 caíram em faturas que o sistema nunca acompanhou.
 */
class CardTrackingStartTest {

    private static final UUID NOTEBOOK = UUID.randomUUID();

    private final CardsTestContext ctx = new CardsTestContext();
    private CardView aurora;
    private CreditCard card;

    @BeforeEach
    void auroraRegisteredOnSeptember22() {
        aurora = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        card = ctx.storedCard(aurora.id());
        ctx.purchase(card, NOTEBOOK, LocalDate.parse("2026-07-12"), "Notebook",
                InstallmentSplitter.split(brl("4800.00"), 10));
        // Outubro (fecha 28 set): 2.932,80 de outras compras + a parcela 3 do notebook = 3.412,80.
        ctx.invoiceWith(card, YearMonth.of(2026, 9), "2932.80");
    }

    @Test
    void theCurrentInvoiceIsTheFirstTracked() {
        assertThat(card.trackingStartMonth()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void installmentsBeforeTheTrackingStartStayOutOfTheUtilization() {
        UtilizationView utilization = ctx.utilization.execute(ctx.anasWorkspaceId(), aurora.id());

        // 09-dados-de-exemplo: (3.412,80 + 3.360,00) ÷ 12.000 = 56%, e 28% sem as parcelas futuras.
        assertThat(utilization.utilized()).isEqualTo(brl("6772.80"));
        assertThat(utilization.utilizationPercent()).isEqualTo(56);
        assertThat(utilization.utilizationWithoutFuturePercent()).isEqualTo(28);
        assertThat(utilization.composition().previousOutstanding()).isEqualTo(brl("0.00"));
        assertThat(utilization.calculationVersion()).isEqualTo("invoice-v2");
    }

    @Test
    void installmentsBeforeTheTrackingStartHaveTheirOwnStatus() {
        InstallmentPlanView plan = ctx.plans.get(ctx.anasWorkspaceId(), aurora.id(), NOTEBOOK);

        assertThat(plan.installments()).extracting(InstallmentPlanView.Item::status).startsWith(
                InstallmentStatus.BEFORE_TRACKING, InstallmentStatus.BEFORE_TRACKING, InstallmentStatus.CURRENT,
                InstallmentStatus.FUTURE);
        assertThat(plan.currentNumber()).isEqualTo(3);
        assertThat(plan.paidCount()).isZero();
        assertThat(plan.remainingAfterCurrent()).isEqualTo(brl("3360.00"));
        assertThat(plan.status()).isEqualTo(InstallmentPlanStatus.ACTIVE);
    }

    @Test
    void invoicesBeforeTheTrackingStartAreNotFound() {
        // Setembro (fecha 28 ago) tem a parcela 2, mas não é acompanhada.
        assertThatThrownBy(() -> ctx.getInvoice.byDueMonth(ctx.anasWorkspaceId(), aurora.id(), "2026-09"))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void paymentsUpToTheLastUntrackedDueDatePaidThatInvoice() {
        // 05 set é o vencimento da fatura de setembro, anterior ao acompanhamento; 06 set já abate outubro.
        ctx.ledger.pay(card.workspaceId(), card.id(), LocalDate.parse("2026-09-05"), brl("2954.10"));
        ctx.ledger.pay(card.workspaceId(), card.id(), LocalDate.parse("2026-09-06"), brl("100.00"));

        UtilizationView utilization = ctx.utilization.execute(ctx.anasWorkspaceId(), aurora.id());

        assertThat(utilization.utilized()).isEqualTo(brl("6672.80"));
    }

    @Test
    void recedingTheTrackingStartBringsTheOlderInvoicesBack() {
        assertThat(ctx.cards.recedeTrackingStart(card.workspaceId(), card.id(), YearMonth.of(2026, 7))).isTrue();
        assertThat(ctx.cards.recedeTrackingStart(card.workspaceId(), card.id(), YearMonth.of(2026, 8))).isFalse();

        UtilizationView utilization = ctx.utilization.execute(ctx.anasWorkspaceId(), aurora.id());

        // As parcelas 1 e 2 (agosto e setembro) voltam como saldo em aberto: nada foi pago.
        assertThat(utilization.composition().previousOutstanding()).isEqualTo(brl("960.00"));
        assertThat(utilization.utilized()).isEqualTo(brl("7732.80"));
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
