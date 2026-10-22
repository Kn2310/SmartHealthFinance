package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.cards.domain.service.CreditAllocator.Allocation;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.AppliedCredit;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Charge;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Credit;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditAllocatorTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final InvoiceCalendar AURORA = new InvoiceCalendar(new BillingCycle(28, 5), List.of());
    private static final UUID NOTEBOOK = UUID.randomUUID();

    /** Notebook, 10 × R$ 480,00, compra em 12 jul: parcelas nas faturas que fecham de jul/2026 a abr/2027. */
    private static List<Charge> notebook() {
        List<Charge> charges = new ArrayList<>();
        for (int number = 1; number <= 10; number++) {
            charges.add(new Charge(NOTEBOOK, number, AURORA.installmentClosingMonth(LocalDate.parse("2026-07-12"),
                    number), brl("480.00")));
        }
        return charges;
    }

    @Test
    void totalRefundCancelsUnbilledInstallmentsFromTheLastAndCreditsTheRest() {
        List<Charge> charges = notebook();
        Credit refund = new Credit(UUID.randomUUID(), LocalDate.parse("2026-09-22"), brl("4800.00"), NOTEBOOK);

        Allocation allocation = CreditAllocator.allocate(charges, List.of(refund), AURORA);

        // Parcelas 4–10 (faturas que começam depois de 22 set) canceladas; 1–3 ficam.
        assertThat(charges.subList(0, 3)).allSatisfy(charge ->
                assertThat(allocation.netOf(charge)).isEqualTo(brl("480.00")));
        assertThat(charges.subList(3, 10)).allSatisfy(charge ->
                assertThat(allocation.netOf(charge)).isEqualTo(brl("0.00")));
        AppliedCredit applied = allocation.credits().getFirst();
        assertThat(applied.consumed()).isEqualTo(brl("3360.00"));
        assertThat(applied.remainder()).isEqualTo(brl("1440.00"));
        assertThat(applied.closingMonth()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void partialRefundCancelsFromTheLastInstallment() {
        List<Charge> charges = notebook();
        Credit refund = new Credit(UUID.randomUUID(), LocalDate.parse("2026-09-22"), brl("700.00"), NOTEBOOK);

        Allocation allocation = CreditAllocator.allocate(charges, List.of(refund), AURORA);

        assertThat(allocation.netOf(charges.get(9))).isEqualTo(brl("0.00"));
        assertThat(allocation.netOf(charges.get(8))).isEqualTo(brl("260.00"));
        assertThat(allocation.netOf(charges.get(7))).isEqualTo(brl("480.00"));
        assertThat(allocation.credits().getFirst().remainder()).isEqualTo(brl("0.00"));
    }

    @Test
    void secondRefundDoesNotConsumeWhatTheFirstAlreadyCancelled() {
        List<Charge> charges = notebook();
        Credit first = new Credit(UUID.randomUUID(), LocalDate.parse("2026-09-20"), brl("480.00"), NOTEBOOK);
        Credit second = new Credit(UUID.randomUUID(), LocalDate.parse("2026-09-21"), brl("480.00"), NOTEBOOK);

        Allocation allocation = CreditAllocator.allocate(charges, List.of(second, first), AURORA);

        assertThat(allocation.netOf(charges.get(9))).isEqualTo(brl("0.00"));
        assertThat(allocation.netOf(charges.get(8))).isEqualTo(brl("0.00"));
        assertThat(allocation.credits()).extracting(applied -> applied.credit().transactionId())
                .containsExactly(first.transactionId(), second.transactionId());
    }

    @Test
    void unlinkedCreditOrBilledPurchaseGoesWhollyToTheInvoiceOfItsDate() {
        Charge cash = new Charge(UUID.randomUUID(), 1, YearMonth.of(2026, 9), brl("200.00"));
        Credit linked = new Credit(UUID.randomUUID(), LocalDate.parse("2026-09-25"), brl("50.00"), cash.transactionId());
        Credit loose = new Credit(UUID.randomUUID(), LocalDate.parse("2026-09-28"), brl("30.00"), null);

        Allocation allocation = CreditAllocator.allocate(List.of(cash), List.of(linked, loose), AURORA);

        assertThat(allocation.netOf(cash)).isEqualTo(brl("200.00"));
        assertThat(allocation.credits()).extracting(AppliedCredit::closingMonth)
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(allocation.credits()).extracting(AppliedCredit::remainder)
                .containsExactly(brl("50.00"), brl("30.00"));
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
