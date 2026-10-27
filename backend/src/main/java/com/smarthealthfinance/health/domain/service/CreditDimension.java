package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.DueInvoice;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.health.domain.enums.EvidenceKind;
import com.smarthealthfinance.health.domain.enums.EvidenceSource;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.valueobject.ScoreCurve;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Credit ({@code health-v2}, ADR-0027 §2): U = Σ utilizado ÷ Σ limite dos cartões ativos acompanhados na data-base
 * (com as parcelas futuras) e p = faturas pagas integralmente até o vencimento efetivo ÷ faturas com vencimento
 * efetivo nos 6 meses até a data-base. Nota = 0,7 · S(U) + 0,3 · 100 · p; sem fatura vencida na janela, Nota = S(U).
 */
final class CreditDimension {

    static final ScoreCurve CURVE = ScoreCurve.of("0", "100", "0.30", "80", "0.60", "55", "0.90", "20", "1", "0");
    static final int PUNCTUALITY_MONTHS = 6;

    static final BigDecimal ATTENTION_UTILIZATION = new BigDecimal("0.50");
    static final BigDecimal ATTENTION_CARD_UTILIZATION = new BigDecimal("0.80");

    /**
     * @param overdue          fatura vencida mais antiga (próximo passo "Ver fatura"); nula sem atraso
     * @param mostUsed         cartão de maior utilização quando o ponto de atenção vem da utilização; nulo senão
     */
    record Assessment(DimensionResult result, Overdue overdue, CardPosition mostUsed) {}

    record Overdue(CardPosition card, InvoiceStatement invoice) {}

    private CreditDimension() {
    }

    static Assessment assess(HealthInput input) {
        List<CardPosition> cards = input.cards();
        if (cards.isEmpty()) {
            return new Assessment(DimensionResult.notApplicable(HealthDimension.CREDIT), null, null);
        }
        LocalDate asOf = input.asOf();
        Money zero = Money.zero(cards.getFirst().limit().currency());
        Money utilized = zero;
        Money limit = zero;
        Money future = zero;
        CardPosition mostUsed = null;
        BigDecimal highest = null;
        Overdue overdue = null;
        int due = 0;
        int onTime = 0;
        LocalDate punctualityFrom = asOf.minusMonths(PUNCTUALITY_MONTHS);

        for (CardPosition card : cards) {
            utilized = utilized.plus(card.utilization().utilized());
            limit = limit.plus(card.limit());
            future = future.plus(card.futureInstallments());

            BigDecimal ratio = Ratios.divide(card.utilization().utilized().amount(), card.limit().amount());
            if (highest == null || ratio.compareTo(highest) > 0) {
                highest = ratio;
                mostUsed = card;
            }
            for (DueInvoice invoice : card.dueInvoices()) {
                if (invoice.effectiveDueDate().isAfter(punctualityFrom)) {
                    due++;
                    onTime += invoice.paidOnTime() ? 1 : 0;
                }
            }
            for (InvoiceStatement statement : card.statements()) {
                if (statement.status() == InvoiceStatus.OVERDUE && (overdue == null
                        || statement.dates().dueDate().isBefore(overdue.invoice().dates().dueDate()))) {
                    overdue = new Overdue(card, statement);
                }
            }
        }

        BigDecimal utilization = Ratios.divide(utilized.amount(), limit.amount());
        BigDecimal utilizationScore = CURVE.score(utilization);
        int score = due == 0 ? Ratios.toScore(utilizationScore)
                : Ratios.toScore(Ratios.weighted("0.7", utilizationScore)
                        .add(Ratios.weighted("0.3", Ratios.divide(onTime, due).multiply(BigDecimal.valueOf(100)))));

        boolean utilizationAttention = utilization.compareTo(ATTENTION_UTILIZATION) >= 0
                || highest.compareTo(ATTENTION_CARD_UTILIZATION) >= 0;
        boolean attention = utilizationAttention || onTime < due || overdue != null;

        StringBuilder explanation = new StringBuilder();
        String percent = wholePercent(utilized, limit);
        if (cards.size() == 1) {
            explanation.append("Utilização de %s do limite do %s".formatted(percent, cards.getFirst().nickname()));
        }
        else {
            explanation.append("Utilização de %s do limite dos seus %d cartões (%s de %s)".formatted(percent,
                    cards.size(), HealthTexts.money(utilized), HealthTexts.money(limit)));
        }
        if (future.isPositive()) {
            explanation.append(", incluindo %s de parcelas futuras".formatted(HealthTexts.money(future)));
        }
        explanation.append('.');
        if (due > 0) {
            explanation.append(" %d de %d %s integralmente até o vencimento.".formatted(onTime, due,
                    due == 1 ? "fatura paga" : "faturas pagas"));
        }

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("utilized", "Limite utilizado, com parcelas futuras", EvidenceKind.MONEY,
                utilized.amount(), asOf, asOf, EvidenceSource.CARDS_UTILIZATION));
        evidence.add(new Evidence("limit", "Limite dos cartões", EvidenceKind.MONEY, limit.amount(), asOf, asOf,
                EvidenceSource.CARDS_UTILIZATION));
        evidence.add(new Evidence("utilization", "Utilização", EvidenceKind.PERCENT,
                HealthTexts.toPercent(utilization).setScale(2), asOf, asOf, EvidenceSource.CARDS_UTILIZATION));
        evidence.add(new Evidence("futureInstallments", "Parcelas futuras", EvidenceKind.MONEY, future.amount(), asOf,
                asOf, EvidenceSource.CARDS_UTILIZATION));
        evidence.add(new Evidence("activeCards", "Cartões", EvidenceKind.COUNT, BigDecimal.valueOf(cards.size()),
                asOf, asOf, EvidenceSource.CARDS_UTILIZATION));
        evidence.add(new Evidence("dueInvoices", "Faturas vencidas no período", EvidenceKind.COUNT,
                BigDecimal.valueOf(due), punctualityFrom.plusDays(1), asOf, EvidenceSource.CARDS_INVOICES));
        evidence.add(new Evidence("paidOnTime", "Faturas pagas até o vencimento", EvidenceKind.COUNT,
                BigDecimal.valueOf(onTime), punctualityFrom.plusDays(1), asOf, EvidenceSource.CARDS_INVOICES));

        return new Assessment(DimensionResult.ok(HealthDimension.CREDIT, score, explanation.toString(), attention,
                evidence), overdue, utilizationAttention ? mostUsed : null);
    }

    /** Utilização inteira (HALF_EVEN) a partir dos valores exatos, como na API de cartões: {@code 56%}. */
    static String wholePercent(Money part, Money whole) {
        return part.amount().multiply(BigDecimal.valueOf(100)).divide(whole.amount(), 0, RoundingMode.HALF_EVEN)
                .toPlainString() + "%";
    }
}
