package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.application.port.CardLedgerReader.CardFacts;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CreditFact;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Allocation;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.AppliedCredit;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Charge;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.service.InvoiceLedger;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tudo o que as leituras de um cartão precisam, calculado uma vez: calendário, faturas persistidas, fatos
 * {@code POSTED}, estornos aplicados e razão.
 *
 * @param charges  uma por parcela dos fatos, com o mês de fechamento da fatura em que cai (mesma ordem dos fatos)
 * @param current  datas da fatura cujo período contém hoje
 */
public record CardLedgerSnapshot(CreditCard card, InvoiceCalendar calendar, List<CardInvoice> rows, CardFacts facts,
                                 List<Charge> charges, Allocation allocation, InvoiceLedger ledger,
                                 InvoiceDates current, LocalDate today) {

    /** Fatura anterior ao início do acompanhamento: fora do razão (ADR-0025 §15). */
    public boolean isBeforeTracking(YearMonth closingMonth) {
        return closingMonth.isBefore(card.trackingStartMonth());
    }

    public Optional<CardInvoice> row(YearMonth closingMonth) {
        return rows.stream().filter(row -> row.dates().closingMonth().equals(closingMonth)).findFirst();
    }

    /**
     * Situação da fatura: a do razão, ou zero para uma fatura futura ainda sem lançamentos. Fatura passada sem
     * lançamentos não existe (vazio).
     */
    public Optional<InvoiceStatement> statement(InvoiceDates dates) {
        Optional<InvoiceStatement> known = ledger.statements().stream()
                .filter(statement -> statement.dates().closingMonth().equals(dates.closingMonth()))
                .findFirst();
        if (known.isPresent() || dates.hasStartedOn(today)) {
            return known;
        }
        Money zero = zero();
        return Optional.of(new InvoiceStatement(dates, InvoiceStatus.FUTURE, zero, zero, zero, zero, zero));
    }

    /** O carregador sempre põe a fatura atual no razão, mesmo sem lançamentos. */
    public InvoiceStatement currentStatement() {
        return statement(current).orElseThrow();
    }

    /** Estado da fatura de um mês de fechamento (FUTURE quando ainda não começou e não tem lançamentos). */
    public InvoiceStatus statusOf(YearMonth closingMonth) {
        return statement(calendar.datesOf(closingMonth)).map(InvoiceStatement::status).orElse(InvoiceStatus.FUTURE);
    }

    /** Valor já comprometido em parcelas na fatura seguinte à atual. */
    public Money nextInvoiceCommitted() {
        InvoiceDates next = calendar.datesOf(current.closingMonth().plusMonths(1));
        return statement(next).map(InvoiceStatement::remaining).orElse(zero());
    }

    /** Parcelas (com o estorno já aplicado) e créditos da fatura, da data mais recente para a mais antiga. */
    public List<InvoiceItemView> items(YearMonth closingMonth) {
        List<InvoiceItemView> items = new ArrayList<>();
        List<InstallmentFact> installments = facts.installments();
        for (int i = 0; i < installments.size(); i++) {
            Charge charge = charges.get(i);
            if (charge.closingMonth().equals(closingMonth)) {
                InstallmentFact fact = installments.get(i);
                items.add(new InvoiceItemView(InvoiceItemKind.CHARGE, fact.transactionId(), fact.purchasedOn(),
                        fact.description(), fact.categoryCode(), fact.merchantName(), fact.source(), fact.number(),
                        fact.count(), fact.amount(), allocation.cancelledOf(charge)));
            }
        }
        Map<UUID, CreditFact> creditsById = new HashMap<>();
        facts.credits().forEach(credit -> creditsById.put(credit.transactionId(), credit));
        for (AppliedCredit applied : allocation.credits()) {
            if (applied.closingMonth().equals(closingMonth)) {
                CreditFact fact = creditsById.get(applied.credit().transactionId());
                items.add(new InvoiceItemView(InvoiceItemKind.CREDIT, fact.transactionId(), fact.occurredOn(),
                        fact.description(), fact.categoryCode(), fact.merchantName(), fact.source(), null, null,
                        fact.amount(), applied.consumed()));
            }
        }
        items.sort(Comparator.comparing(InvoiceItemView::occurredOn).reversed()
                .thenComparing(item -> item.installmentNumber() == null ? 0 : item.installmentNumber())
                .thenComparing(item -> item.transactionId().toString()));
        return items;
    }

    private Money zero() {
        return Money.zero(card.limit().value().currency());
    }
}
