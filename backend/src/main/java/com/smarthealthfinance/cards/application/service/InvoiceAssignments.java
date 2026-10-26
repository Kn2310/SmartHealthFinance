package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.cards.domain.model.CardInstallment;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInstallmentRepository;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Atribuição de parcelas a faturas (ADR-0025 §5/§6, ADR-0026 §10): cria as linhas de fatura que faltam e, na mudança
 * de ciclo, move as parcelas das faturas não fechadas. Sempre na transação de banco de quem chama.
 */
@Service
public class InvoiceAssignments {

    private final CardInvoiceRepository invoices;
    private final CardInstallmentRepository installments;
    private final Clock clock;

    public InvoiceAssignments(CardInvoiceRepository invoices, CardInstallmentRepository installments, Clock clock) {
        this.invoices = invoices;
        this.installments = installments;
        this.clock = clock;
    }

    /** Compra a distribuir nas faturas. */
    public record Purchase(UUID transactionId, LocalDate purchasedOn, List<Money> parts) {
    }

    /** A parcela {@code k} cai na fatura do ciclo da compra deslocada {@code k − 1} meses. */
    public List<CardInstallment> assignPurchase(CreditCard card, UUID transactionId, LocalDate purchasedOn,
                                                List<Money> parts) {
        return assignPurchases(card, List.of(new Purchase(transactionId, purchasedOn, parts)));
    }

    /** Várias compras com uma só leitura das faturas (importação de fatura, ADR-0009 §26). */
    public List<CardInstallment> assignPurchases(CreditCard card, List<Purchase> purchases) {
        Rows rows = new Rows(card);
        List<CardInstallment> created = new ArrayList<>();
        for (Purchase purchase : purchases) {
            List<Money> parts = purchase.parts();
            for (int number = 1; number <= parts.size(); number++) {
                YearMonth month = rows.calendar().installmentClosingMonth(purchase.purchasedOn(), number);
                created.add(new CardInstallment(card.workspaceId(), card.id(), purchase.transactionId(), number,
                        parts.size(), purchase.purchasedOn(), parts.get(number - 1), rows.ensure(month),
                        InvoiceCalendar.RULES_VERSION));
            }
        }
        installments.addAll(created);
        return created;
    }

    /**
     * Depois de recalculadas as datas das faturas não fechadas: cada parcela que estava numa delas vai para a fatura
     * que a regra nova indica, nunca para uma fatura já fechada (a primeira não fechada recebe). Faturas não fechadas
     * que ficarem vazias são removidas.
     *
     * @return quantas parcelas mudaram de fatura
     */
    public int reassignOpen(CreditCard card, LocalDate today) {
        Rows rows = new Rows(card);
        Set<CardInvoiceId> frozen = new HashSet<>();
        YearMonth lastFrozen = null;
        for (CardInvoice row : rows.all()) {
            if (row.dates().isClosedOn(today)) {
                frozen.add(row.id());
                lastFrozen = row.dates().closingMonth();
            }
        }

        List<CardInstallment> moved = new ArrayList<>();
        Set<CardInvoiceId> used = new HashSet<>();
        for (CardInstallment installment : installments.findByCard(card.workspaceId(), card.id())) {
            if (frozen.contains(installment.invoiceId())) {
                used.add(installment.invoiceId());
                continue;
            }
            YearMonth target = rows.calendar().installmentClosingMonth(installment.purchasedOn(),
                    installment.number());
            if (lastFrozen != null && !target.isAfter(lastFrozen)) {
                target = lastFrozen.plusMonths(1);
            }
            CardInvoiceId invoice = rows.ensure(target);
            used.add(invoice);
            if (!invoice.equals(installment.invoiceId())) {
                moved.add(installment.movedTo(invoice, InvoiceCalendar.RULES_VERSION));
            }
        }
        installments.updateInvoices(moved);
        invoices.deleteAll(rows.all().stream()
                .filter(row -> !frozen.contains(row.id()) && !used.contains(row.id()))
                .toList());
        return moved.size();
    }

    /** Faturas persistidas do cartão, criando as que faltam com as datas da regra atual. */
    private final class Rows {

        private final CreditCard card;
        private final Map<YearMonth, CardInvoice> byMonth = new TreeMap<>();
        private InvoiceCalendar calendar;

        Rows(CreditCard card) {
            this.card = card;
            invoices.findByCard(card.workspaceId(), card.id())
                    .forEach(row -> byMonth.put(row.dates().closingMonth(), row));
            this.calendar = new InvoiceCalendar(card.cycle(), byMonth.values().stream().map(CardInvoice::dates)
                    .toList());
        }

        InvoiceCalendar calendar() {
            return calendar;
        }

        List<CardInvoice> all() {
            return List.copyOf(byMonth.values());
        }

        CardInvoiceId ensure(YearMonth month) {
            CardInvoice existing = byMonth.get(month);
            if (existing != null) {
                return existing.id();
            }
            CardInvoice created = new CardInvoice(CardInvoiceId.generate(clock.instant()), card.workspaceId(),
                    card.id(), calendar.datesOf(month), null, null);
            invoices.add(created);
            byMonth.put(month, created);
            calendar = new InvoiceCalendar(card.cycle(), byMonth.values().stream().map(CardInvoice::dates).toList());
            return created.id();
        }
    }
}
