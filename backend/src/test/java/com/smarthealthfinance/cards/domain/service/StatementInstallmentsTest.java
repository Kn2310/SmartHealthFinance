package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.cards.domain.service.StatementInstallments.Candidate;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.InstallmentKey;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Parcela "k de n" da fatura importada (ADR-0009 §25–§26), com o Aurora (fecha 28, vence 05). */
class StatementInstallmentsTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final InvoiceCalendar AURORA = new InvoiceCalendar(new BillingCycle(28, 5), List.of());
    /** Fatura de outubro: fecha 28 set. */
    private static final YearMonth OCTOBER_INVOICE = YearMonth.of(2026, 9);

    private static final UUID NOTEBOOK = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Test
    void theLineDateIsThePurchaseDateWhenTheInstallmentFallsInTheFileInvoice() {
        // Notebook de 12 jul: parcela 3 na fatura de outubro.
        assertThat(StatementInstallments.purchaseDate(AURORA, LocalDate.of(2026, 7, 12), 3, OCTOBER_INVOICE))
                .isEqualTo(LocalDate.of(2026, 7, 12));
    }

    @Test
    void otherwiseTheLineDateIsThePostingDate() {
        // O banco lançou a parcela 3 em 12 set: a compra foi em 12 jul.
        assertThat(StatementInstallments.purchaseDate(AURORA, LocalDate.of(2026, 9, 12), 3, OCTOBER_INVOICE))
                .isEqualTo(LocalDate.of(2026, 7, 12));
        // Dia limitado ao fim do mês.
        assertThat(StatementInstallments.purchaseDate(AURORA, LocalDate.of(2026, 3, 31), 2,
                YearMonth.of(2026, 3))).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void matchesAPlanWithTheSameCountInstallmentAndInvoice() {
        List<Candidate> candidates = List.of(
                candidate(NOTEBOOK, "2026-07-12", 3, 10, OCTOBER_INVOICE, "480.00"),
                candidate(OTHER, "2026-07-12", 3, 12, OCTOBER_INVOICE, "480.00"));

        assertThat(StatementInstallments.match(candidates, Set.of(), 3, 10, OCTOBER_INVOICE, brl("480.00")))
                .contains(NOTEBOOK);
        assertThat(StatementInstallments.match(candidates, Set.of(), 3, 10, YearMonth.of(2026, 10), brl("480.00")))
                .isEmpty();
    }

    @Test
    void toleratesLessThanOneCentPerInstallment() {
        // 100,00 em 3×: 33,34 + 33,33 + 33,33. A linha da 1ª parcela pode trazer 33,33 de outro arredondamento.
        List<Candidate> candidates = List.of(candidate(NOTEBOOK, "2026-09-10", 1, 3, OCTOBER_INVOICE, "33.34"));

        assertThat(StatementInstallments.match(candidates, Set.of(), 1, 3, OCTOBER_INVOICE, brl("33.33")))
                .contains(NOTEBOOK);
        assertThat(StatementInstallments.match(candidates, Set.of(), 1, 3, OCTOBER_INVOICE, brl("33.37")))
                .isEmpty();
    }

    @Test
    void anInstallmentIsConfirmedOnlyOnce() {
        List<Candidate> candidates = List.of(
                candidate(NOTEBOOK, "2026-07-12", 3, 10, OCTOBER_INVOICE, "480.00"),
                candidate(OTHER, "2026-07-20", 3, 10, OCTOBER_INVOICE, "480.00"));

        // Duas compras iguais: a mais antiga primeiro; depois de confirmada, a outra.
        assertThat(StatementInstallments.match(candidates, Set.of(), 3, 10, OCTOBER_INVOICE, brl("480.00")))
                .contains(NOTEBOOK);
        assertThat(StatementInstallments.match(candidates, Set.of(new InstallmentKey(NOTEBOOK, 3)), 3, 10,
                OCTOBER_INVOICE, brl("480.00"))).contains(OTHER);
        assertThat(StatementInstallments.match(candidates,
                Set.of(new InstallmentKey(NOTEBOOK, 3), new InstallmentKey(OTHER, 3)), 3, 10, OCTOBER_INVOICE,
                brl("480.00"))).isEmpty();
    }

    private static Candidate candidate(UUID id, String purchasedOn, int number, int count, YearMonth month,
                                       String amount) {
        return new Candidate(id, LocalDate.parse(purchasedOn), number, count, month, brl(amount));
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
