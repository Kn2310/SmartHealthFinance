package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardUtilization;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceBalance;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceLedgerTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final BillingCycle AURORA = new BillingCycle(28, 5);
    private static final InvoiceCalendar CALENDAR = new InvoiceCalendar(AURORA, List.of());
    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");
    private static final CardLimit LIMIT = new CardLimit(brl("12000.00"));

    /**
     * Aurora Platinum (09-dados-de-exemplo): faturas pagas de jul (2.710,40), ago (2.954,10) e set (3.012,10), fatura
     * de outubro aberta com R$ 3.412,80 e as parcelas 4–10 do notebook (7 × R$ 480,00) nas faturas de nov a mai.
     */
    @Test
    void auroraSampleData() {
        InvoiceLedger ledger = InvoiceLedger.compute(auroraInvoices(), brl("8676.60"), TODAY);

        assertThat(ledger.statements()).extracting(InvoiceStatement::status).containsExactly(
                InvoiceStatus.PAID, InvoiceStatus.PAID, InvoiceStatus.PAID, InvoiceStatus.OPEN,
                InvoiceStatus.FUTURE, InvoiceStatus.FUTURE, InvoiceStatus.FUTURE, InvoiceStatus.FUTURE,
                InvoiceStatus.FUTURE, InvoiceStatus.FUTURE, InvoiceStatus.FUTURE);
        assertThat(ledger.current()).get().satisfies(current -> {
            assertThat(current.dates().dueMonth()).isEqualTo(YearMonth.of(2026, 10));
            assertThat(current.remaining()).isEqualTo(brl("3412.80"));
        });
        assertThat(ledger.openRemaining()).isEqualTo(brl("3412.80"));

        CardUtilization utilization = ledger.utilization(LIMIT);
        assertThat(utilization.utilized()).isEqualTo(brl("6772.80"));
        assertThat(utilization.available()).isEqualTo(brl("5227.20"));
        assertThat(utilization.utilizationPercent()).isEqualTo(56);
        assertThat(utilization.utilizationWithoutFuturePercent()).isEqualTo(28);
        assertThat(utilization.previousOutstanding()).isEqualTo(brl("0.00"));
        assertThat(utilization.currentInvoice()).isEqualTo(brl("3412.80"));
        assertThat(utilization.futureInstallments()).isEqualTo(brl("3360.00"));
        assertThat(utilization.credit()).isEqualTo(brl("0.00"));
    }

    @ParameterizedTest(name = "hoje {0}, pago {1} → {2}")
    @CsvSource({
            // Fatura de outubro do Aurora: fecha 28 set, vence 05 out (segunda), valor 3.412,80.
            "2026-09-27, 0.00, OPEN",
            "2026-09-27, 3412.80, OPEN",
            "2026-09-28, 0.00, CLOSED",
            "2026-09-30, 1000.00, PARTIALLY_PAID",
            "2026-10-05, 1000.00, PARTIALLY_PAID",
            "2026-10-06, 1000.00, OVERDUE",
            "2026-10-06, 0.00, OVERDUE",
            "2026-10-06, 3412.80, PAID",
            "2026-09-29, 5000.00, PAID",
    })
    void statusOfTheCurrentInvoice(String today, String paid, InvoiceStatus expected) {
        InvoiceBalance october = balance(YearMonth.of(2026, 9), "3412.80");

        InvoiceLedger ledger = InvoiceLedger.compute(List.of(october), brl(paid), LocalDate.parse(today));

        assertThat(ledger.statements().getFirst().status()).isEqualTo(expected);
    }

    @Test
    void dueDateOnTheWeekendIsOverdueOnlyAfterMonday() {
        InvoiceCalendar calendar = new InvoiceCalendar(new BillingCycle(26, 3), List.of());
        // Fecha 26 set, vence sábado 03 out; vencimento efetivo segunda 05 out.
        InvoiceBalance invoice = new InvoiceBalance(calendar.datesOf(YearMonth.of(2026, 9)), brl("100.00"),
                brl("0.00"));

        assertThat(InvoiceLedger.compute(List.of(invoice), brl("0.00"), LocalDate.parse("2026-10-05"))
                .statements().getFirst().status()).isEqualTo(InvoiceStatus.CLOSED);
        assertThat(InvoiceLedger.compute(List.of(invoice), brl("0.00"), LocalDate.parse("2026-10-06"))
                .statements().getFirst().status()).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    void paymentsFillTheOldestInvoiceFirstAndTheRestIsCredit() {
        List<InvoiceBalance> invoices = List.of(balance(YearMonth.of(2026, 7), "100.00"),
                balance(YearMonth.of(2026, 8), "200.00"), balance(YearMonth.of(2026, 9), "50.00"),
                balance(YearMonth.of(2026, 10), "40.00"));

        InvoiceLedger ledger = InvoiceLedger.compute(invoices, brl("420.00"), TODAY);

        assertThat(ledger.statements()).extracting(InvoiceStatement::paid)
                .containsExactly(brl("100.00"), brl("200.00"), brl("50.00"), brl("0.00"));
        CardUtilization utilization = ledger.utilization(LIMIT);
        // Sobram 70,00: abatem os 40,00 futuros e ficam 30,00 de crédito.
        assertThat(utilization.utilized()).isEqualTo(brl("0.00"));
        assertThat(utilization.futureInstallments()).isEqualTo(brl("0.00"));
        assertThat(utilization.credit()).isEqualTo(brl("30.00"));
        assertThat(utilization.available()).isEqualTo(brl("12000.00"));
    }

    @Test
    void partialPaymentLeavesThePreviousInvoiceOutstanding() {
        List<InvoiceBalance> invoices = List.of(balance(YearMonth.of(2026, 8), "1000.00"),
                balance(YearMonth.of(2026, 9), "500.00"), balance(YearMonth.of(2026, 10), "300.00"));

        InvoiceLedger ledger = InvoiceLedger.compute(invoices, brl("400.00"), TODAY);

        assertThat(ledger.statements().getFirst().status()).isEqualTo(InvoiceStatus.OVERDUE);
        CardUtilization utilization = ledger.utilization(LIMIT);
        assertThat(utilization.previousOutstanding()).isEqualTo(brl("600.00"));
        assertThat(utilization.currentInvoice()).isEqualTo(brl("500.00"));
        assertThat(utilization.futureInstallments()).isEqualTo(brl("300.00"));
        assertThat(utilization.utilized()).isEqualTo(brl("1400.00"));
        assertThat(utilization.previousOutstanding().plus(utilization.currentInvoice())
                .plus(utilization.futureInstallments())).isEqualTo(utilization.utilized());
    }

    @Test
    void invoiceWithMoreCreditsThanChargesCarriesTheCreditForward() {
        InvoiceBalance refunded = new InvoiceBalance(CALENDAR.datesOf(YearMonth.of(2026, 8)), brl("100.00"),
                brl("250.00"));
        InvoiceBalance october = balance(YearMonth.of(2026, 9), "120.00");

        InvoiceLedger ledger = InvoiceLedger.compute(List.of(refunded, october), brl("0.00"), TODAY);

        assertThat(ledger.statements().getFirst().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(ledger.statements().getFirst().amount()).isEqualTo(brl("-150.00"));
        assertThat(ledger.statements().get(1).paid()).isEqualTo(brl("120.00"));
        assertThat(ledger.utilization(LIMIT).credit()).isEqualTo(brl("30.00"));
    }

    @Test
    void utilizationAboveTheLimitHasNegativeAvailable() {
        InvoiceLedger ledger = InvoiceLedger.compute(List.of(balance(YearMonth.of(2026, 9), "12600.00")),
                brl("0.00"), TODAY);

        CardUtilization utilization = ledger.utilization(LIMIT);
        assertThat(utilization.available()).isEqualTo(brl("-600.00"));
        assertThat(utilization.utilizationPercent()).isEqualTo(105);
    }

    @Test
    void percentRoundsHalfToEven() {
        // 6,25 ÷ 50 = 12,5% → 12; 6,75 ÷ 50 = 13,5% → 14.
        CardLimit fifty = new CardLimit(brl("50.00"));
        assertThat(InvoiceLedger.compute(List.of(balance(YearMonth.of(2026, 9), "6.25")), brl("0.00"), TODAY)
                .utilization(fifty).utilizationPercent()).isEqualTo(12);
        assertThat(InvoiceLedger.compute(List.of(balance(YearMonth.of(2026, 9), "6.75")), brl("0.00"), TODAY)
                .utilization(fifty).utilizationPercent()).isEqualTo(14);
    }

    private static List<InvoiceBalance> auroraInvoices() {
        List<InvoiceBalance> invoices = new ArrayList<>();
        invoices.add(balance(YearMonth.of(2026, 6), "2710.40"));
        invoices.add(balance(YearMonth.of(2026, 7), "2954.10"));
        invoices.add(balance(YearMonth.of(2026, 8), "3012.10"));
        invoices.add(balance(YearMonth.of(2026, 9), "3412.80"));
        for (int month = 10; month <= 16; month++) {
            invoices.add(balance(YearMonth.of(2026, 1).plusMonths(month - 1L), "480.00"));
        }
        return invoices;
    }

    private static InvoiceBalance balance(YearMonth closingMonth, String charges) {
        return new InvoiceBalance(CALENDAR.datesOf(closingMonth), brl(charges), brl("0.00"));
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
