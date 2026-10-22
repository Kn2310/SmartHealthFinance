package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calendário das faturas de um cartão ({@code invoice-v1}, ADR-0025 §5, ADR-0026 §5). Puro e determinístico.
 * <p>
 * Uma fatura é identificada internamente pelo mês de fechamento. As datas vêm da linha persistida, quando existe
 * (inclusive as congeladas de faturas já fechadas); senão, da regra atual do cartão. O início do período é sempre o
 * fechamento da fatura anterior.
 */
public final class InvoiceCalendar {

    public static final String RULES_VERSION = "invoice-v1";

    /** Teto de passos na busca da fatura de uma data; o ciclo anda um mês por passo. */
    private static final int MAX_STEPS = 48;

    private final BillingCycle cycle;
    private final NavigableMap<YearMonth, InvoiceDates> stored;

    public InvoiceCalendar(BillingCycle cycle, Collection<InvoiceDates> storedDates) {
        this.cycle = Objects.requireNonNull(cycle, "cycle");
        this.stored = new TreeMap<>();
        for (InvoiceDates dates : storedDates) {
            this.stored.put(dates.closingMonth(), dates);
        }
    }

    public BillingCycle cycle() {
        return cycle;
    }

    public InvoiceDates datesOf(YearMonth closingMonth) {
        InvoiceDates saved = stored.get(closingMonth);
        return saved != null ? saved : computed(closingMonth);
    }

    /** A fatura cujo período {@code [início, fechamento)} contém a data. */
    public YearMonth closingMonthOf(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        Set<YearMonth> visited = new HashSet<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            InvoiceDates dates = datesOf(month);
            if (dates.contains(date)) {
                return month;
            }

            YearMonth next = date.isBefore(dates.periodStart()) ? month.minusMonths(1) : month.plusMonths(1);
            if (!visited.add(month) && visited.contains(next)) {
                // Lacuna entre uma fatura congelada e a vizinha calculada pela regra nova: fica com a mais recente.
                return month.isAfter(next) ? month : next;
            }
            month = next;
        }
        throw new IllegalStateException("Fatura não encontrada para a data");
    }

    public InvoiceDates invoiceOf(LocalDate date) {
        return datesOf(closingMonthOf(date));
    }

    /** A fatura chamada pelo mês de vencimento ("Fatura de outubro"), se houver uma com esse vencimento. */
    public Optional<InvoiceDates> byDueMonth(YearMonth dueMonth) {
        for (InvoiceDates dates : stored.values()) {
            if (dates.dueMonth().equals(dueMonth)) {
                return Optional.of(dates);
            }
        }

        InvoiceDates candidate = datesOf(cycle.closingMonthOfDueMonth(dueMonth));
        return candidate.dueMonth().equals(dueMonth) ? Optional.of(candidate) : Optional.empty();
    }

    /** Fatura da parcela {@code number} (1 = a do ciclo da compra), ADR-0025 §6. */
    public YearMonth installmentClosingMonth(LocalDate purchasedOn, int number) {
        if (number < 1) {
            throw new IllegalArgumentException("number");
        }
        return closingMonthOf(purchasedOn).plusMonths(number - 1L);
    }

    /**
     * Novas datas das faturas persistidas ainda não fechadas, pela regra de {@code newCycle}, em ordem de fechamento
     * (ADR-0025 §5). As fechadas ({@code closingDate ≤ hoje}) e as importadas ficam como estão.
     *
     * @throws InvalidValueException {@code dueDay DUE_MONTH_CONFLICT} se duas faturas passariam a vencer no mesmo mês
     */
    public static List<CardInvoice> recalculateOpen(List<CardInvoice> invoices, BillingCycle newCycle,
                                                    LocalDate today) {
        NavigableMap<YearMonth, CardInvoice> byMonth = new TreeMap<>();
        for (CardInvoice invoice : invoices) {
            byMonth.put(invoice.dates().closingMonth(), invoice);
        }

        NavigableMap<YearMonth, InvoiceDates> current = new TreeMap<>();
        List<CardInvoice> changed = new ArrayList<>();

        for (Map.Entry<YearMonth, CardInvoice> entry : byMonth.entrySet()) {
            CardInvoice invoice = entry.getValue();
            InvoiceDates dates = invoice.dates();

            if (!dates.isClosedOn(today) && dates.source() == InvoiceDatesSource.CALCULATED) {
                InvoiceDates recalculated = new InvoiceCalendar(newCycle, current.values()).computed(entry.getKey());
                if (!recalculated.equals(dates)) {
                    invoice = invoice.withDates(recalculated);
                    changed.add(invoice);
                }
            }
            current.put(entry.getKey(), invoice.dates());
        }

        Set<YearMonth> dueMonths = new HashSet<>();
        for (InvoiceDates dates : current.values()) {
            if (!dueMonths.add(dates.dueMonth())) {
                throw new InvalidValueException("dueDay", "DUE_MONTH_CONFLICT");
            }
        }
        return changed;
    }

    private InvoiceDates computed(YearMonth closingMonth) {
        YearMonth previous = closingMonth.minusMonths(1);
        InvoiceDates previousSaved = stored.get(previous);
        LocalDate periodStart = previousSaved != null ? previousSaved.closingDate() : cycle.closingDate(previous);

        return new InvoiceDates(closingMonth, cycle.dueMonth(closingMonth), periodStart,
                cycle.closingDate(closingMonth), cycle.dueDate(closingMonth), InvoiceDatesSource.CALCULATED);
    }
}
