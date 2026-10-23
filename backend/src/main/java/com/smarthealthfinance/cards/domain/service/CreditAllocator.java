package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Aplica os estornos do cartão às parcelas e às faturas ({@code invoice-v1}, ADR-0025 §8 e adendo). Puro e
 * determinístico; roda a cada leitura, sobre os fatos {@code POSTED}, e nunca é persistido.
 * <ul>
 *   <li>estorno vinculado a uma compra consome primeiro as parcelas dela ainda não faturadas (fatura que começa depois
 *   da data do estorno), da última para a primeira;</li>
 *   <li>o restante (ou todo o estorno, se não houver vínculo) vira crédito na fatura do ciclo da data do estorno.</li>
 * </ul>
 * Os estornos são aplicados em ordem de data e id, e um não consome o que outro já consumiu.
 */
public final class CreditAllocator {

    /** Parcela de uma compra {@code POSTED}, já atribuída a uma fatura. */
    public record Charge(UUID transactionId, int number, YearMonth closingMonth, Money amount) {
        public Charge {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(closingMonth, "closingMonth");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** Estorno {@code POSTED} no cartão. */
    public record Credit(UUID transactionId, LocalDate occurredOn, Money amount, UUID refundOfTransactionId) {
        public Credit {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(occurredOn, "occurredOn");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * Para onde foi cada estorno.
     *
     * @param consumed  parte que cancelou parcelas futuras da compra
     * @param remainder parte creditada na fatura {@code closingMonth} (a do ciclo da data do estorno)
     */
    public record AppliedCredit(Credit credit, Money consumed, YearMonth closingMonth, Money remainder) {}

    public record ChargeKey(UUID transactionId, int number) {}

    /**
     * @param cancelled quanto de cada parcela foi consumido por estornos (só as afetadas aparecem)
     * @param credits   cada estorno, na ordem em que foi aplicado
     */
    public record Allocation(Map<ChargeKey, Money> cancelled, List<AppliedCredit> credits) {

        public Money cancelledOf(Charge charge) {
            return cancelled.getOrDefault(new ChargeKey(charge.transactionId(), charge.number()),
                    Money.zero(charge.amount().currency()));
        }

        /** Valor da parcela que continua na fatura. */
        public Money netOf(Charge charge) {
            return charge.amount().minus(cancelledOf(charge));
        }
    }

    private CreditAllocator() {
    }

    public static Allocation allocate(List<Charge> charges, List<Credit> credits, InvoiceCalendar calendar) {
        Map<UUID, List<Charge>> byPurchase = new HashMap<>();
        for (Charge charge : charges) {
            byPurchase.computeIfAbsent(charge.transactionId(), ignored -> new ArrayList<>()).add(charge);
        }

        Map<ChargeKey, Money> cancelled = new HashMap<>();
        List<AppliedCredit> applied = new ArrayList<>();

        List<Credit> ordered = new ArrayList<>(credits);
        ordered.sort(Comparator.comparing(Credit::occurredOn).thenComparing(credit -> credit.transactionId().toString()));

        for (Credit credit : ordered) {
            Money zero = Money.zero(credit.amount().currency());
            Money remaining = credit.amount();

            if (credit.refundOfTransactionId() != null) {
                List<Charge> unbilled = byPurchase.getOrDefault(credit.refundOfTransactionId(), List.of()).stream()
                        .filter(charge -> calendar.datesOf(charge.closingMonth()).periodStart()
                                .isAfter(credit.occurredOn()))
                        .sorted(Comparator.comparingInt(Charge::number).reversed())
                        .toList();
                for (Charge charge : unbilled) {
                    if (!remaining.isPositive()) {
                        break;
                    }
                    ChargeKey key = new ChargeKey(charge.transactionId(), charge.number());
                    Money already = cancelled.getOrDefault(key, zero);
                    Money open = charge.amount().minus(already);
                    Money take = open.isGreaterThan(remaining) ? remaining : open;
                    if (take.isPositive()) {
                        cancelled.put(key, already.plus(take));
                        remaining = remaining.minus(take);
                    }
                }
            }

            applied.add(new AppliedCredit(credit, credit.amount().minus(remaining),
                    calendar.closingMonthOf(credit.occurredOn()), remaining));
        }

        return new Allocation(Map.copyOf(cancelled), List.copyOf(applied));
    }
}
