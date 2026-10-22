package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class InvoiceCalendarTest {

    private static final BillingCycle AURORA = new BillingCycle(28, 5);
    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");

    @ParameterizedTest(name = "compra em {0} → fatura com vencimento em {1}")
    @CsvSource({
            // Fatura de outubro do Aurora: [28 ago, 28 set).
            "2026-09-22, 2026-10",
            "2026-08-28, 2026-10",
            "2026-09-27, 2026-10",
            // Compra no dia do fechamento vai para a próxima fatura (ADR-0025 §5).
            "2026-09-28, 2026-11",
            "2026-08-27, 2026-09",
            // Virada de ano.
            "2026-12-28, 2027-02",
            "2026-12-31, 2027-02",
            "2027-01-01, 2027-02",
    })
    void assignsPurchasesByCycle(String purchasedOn, String dueMonth) {
        InvoiceCalendar calendar = new InvoiceCalendar(AURORA, List.of());

        assertThat(calendar.invoiceOf(LocalDate.parse(purchasedOn)).dueMonth()).isEqualTo(YearMonth.parse(dueMonth));
    }

    @Test
    void currentAuroraInvoiceMatchesTheSampleData() {
        InvoiceDates october = new InvoiceCalendar(AURORA, List.of()).invoiceOf(TODAY);

        assertThat(october.dueMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(october.periodStart()).isEqualTo(LocalDate.parse("2026-08-28"));
        assertThat(october.closingDate()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(october.dueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(october.effectiveDueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(october.isClosedOn(TODAY)).isFalse();
    }

    @Test
    void installmentsOfTheNotebookFallInAugustThroughMay() {
        // Notebook, 10× R$ 480,00, compra em 12 jul (09-dados-de-exemplo).
        InvoiceCalendar calendar = new InvoiceCalendar(AURORA, List.of());
        LocalDate purchase = LocalDate.parse("2026-07-12");

        assertThat(calendar.datesOf(calendar.installmentClosingMonth(purchase, 1)).dueMonth())
                .isEqualTo(YearMonth.of(2026, 8));
        assertThat(calendar.datesOf(calendar.installmentClosingMonth(purchase, 3)).dueMonth())
                .isEqualTo(YearMonth.of(2026, 10));
        assertThat(calendar.datesOf(calendar.installmentClosingMonth(purchase, 10)).dueMonth())
                .isEqualTo(YearMonth.of(2027, 5));
    }

    @Test
    void shortMonthsChainThePeriods() {
        InvoiceCalendar calendar = new InvoiceCalendar(new BillingCycle(31, 10), List.of());

        InvoiceDates february = calendar.datesOf(YearMonth.of(2027, 2));
        InvoiceDates march = calendar.datesOf(YearMonth.of(2027, 3));

        assertThat(february.periodStart()).isEqualTo(LocalDate.parse("2027-01-31"));
        assertThat(february.closingDate()).isEqualTo(LocalDate.parse("2027-02-28"));
        assertThat(march.periodStart()).isEqualTo(february.closingDate());
        assertThat(calendar.closingMonthOf(LocalDate.parse("2027-02-28"))).isEqualTo(YearMonth.of(2027, 3));
        assertThat(calendar.closingMonthOf(LocalDate.parse("2027-02-27"))).isEqualTo(YearMonth.of(2027, 2));
    }

    @Test
    void effectiveDueDateSkipsTheWeekend() {
        InvoiceCalendar calendar = new InvoiceCalendar(new BillingCycle(26, 3), List.of());

        InvoiceDates dates = calendar.datesOf(YearMonth.of(2026, 9));

        assertThat(dates.dueDate()).isEqualTo(LocalDate.parse("2026-10-03")); // sábado
        assertThat(dates.effectiveDueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
    }

    @Test
    void findsInvoicesByDueMonth() {
        InvoiceCalendar calendar = new InvoiceCalendar(AURORA, List.of());

        assertThat(calendar.byDueMonth(YearMonth.of(2026, 10))).get()
                .extracting(InvoiceDates::closingMonth).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void storedDatesWinOverTheCurrentRule() {
        InvoiceDates frozen = new InvoiceDates(YearMonth.of(2026, 8), YearMonth.of(2026, 9),
                LocalDate.parse("2026-07-28"), LocalDate.parse("2026-08-28"), LocalDate.parse("2026-09-05"),
                InvoiceDatesSource.CALCULATED);
        InvoiceCalendar calendar = new InvoiceCalendar(new BillingCycle(10, 20), List.of(frozen));

        assertThat(calendar.datesOf(YearMonth.of(2026, 8))).isEqualTo(frozen);
        // A fatura seguinte começa no fechamento congelado e fecha pela regra nova.
        InvoiceDates september = calendar.datesOf(YearMonth.of(2026, 9));
        assertThat(september.periodStart()).isEqualTo(LocalDate.parse("2026-08-28"));
        assertThat(september.closingDate()).isEqualTo(LocalDate.parse("2026-09-10"));
        assertThat(calendar.closingMonthOf(LocalDate.parse("2026-09-01"))).isEqualTo(YearMonth.of(2026, 9));
        assertThat(calendar.closingMonthOf(LocalDate.parse("2026-09-15"))).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void recalculatesOnlyInvoicesThatAreNotClosed() {
        List<CardInvoice> rows = auroraRows();

        List<CardInvoice> changed = InvoiceCalendar.recalculateOpen(rows, new BillingCycle(25, 2), TODAY);

        // A fatura de setembro (fechada em 28 ago) fica congelada; outubro e novembro seguem a regra nova.
        assertThat(changed).extracting(row -> row.dates().closingMonth())
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        InvoiceDates october = changed.get(0).dates();
        assertThat(october.periodStart()).isEqualTo(LocalDate.parse("2026-08-28"));
        assertThat(october.closingDate()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(october.dueDate()).isEqualTo(LocalDate.parse("2026-10-02"));
        InvoiceDates november = changed.get(1).dates();
        assertThat(november.periodStart()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(november.closingDate()).isEqualTo(LocalDate.parse("2026-10-25"));
    }

    @Test
    void refusesACycleThatWouldMakeTwoInvoicesDueInTheSameMonth() {
        // Fecha 28/vence 05 → fecha 20/vence 30: a fatura aberta passaria a vencer em setembro, junto com a fechada.
        assertInvalidValue(() -> InvoiceCalendar.recalculateOpen(auroraRows(), new BillingCycle(20, 30), TODAY),
                "dueDay", "DUE_MONTH_CONFLICT");
    }

    @Test
    void unchangedCycleChangesNothing() {
        assertThat(InvoiceCalendar.recalculateOpen(auroraRows(), AURORA, TODAY)).isEmpty();
    }

    private static List<CardInvoice> auroraRows() {
        InvoiceCalendar calendar = new InvoiceCalendar(AURORA, List.of());
        WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
        CardId card = new CardId(UUID.randomUUID());
        return List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)).stream()
                .map(month -> new CardInvoice(new CardInvoiceId(UUID.randomUUID()), workspace, card,
                        calendar.datesOf(month), null, null))
                .toList();
    }
}
