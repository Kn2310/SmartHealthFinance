package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.cards.domain.valueobject.InstallmentKey;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Parcela "k de n" lida numa fatura importada (ADR-0009 §25–§26): a data da compra e a conciliação com um
 * parcelamento já lançado. Puro e determinístico.
 */
public final class StatementInstallments {

    private StatementInstallments() {
    }

    /** Parcela de uma compra {@code POSTED} do cartão, com o mês de fechamento da fatura em que cai. */
    public record Candidate(UUID transactionId, LocalDate purchasedOn, int number, int count, YearMonth closingMonth,
                            Money amount) {
        public Candidate {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(purchasedOn, "purchasedOn");
            Objects.requireNonNull(closingMonth, "closingMonth");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * Data da compra de uma parcela (§25). Se a parcela {@code k}, contada da data da linha, cai na fatura do arquivo,
     * a linha traz a data da compra; senão traz a data de lançamento, e a compra foi {@code k − 1} meses antes (o dia
     * é limitado ao fim do mês).
     *
     * @param fileClosingMonth fatura do arquivo: a do ciclo da maior data entre as linhas
     */
    public static LocalDate purchaseDate(InvoiceCalendar calendar, LocalDate lineDate, int number,
                                         YearMonth fileClosingMonth) {
        if (calendar.installmentClosingMonth(lineDate, number).equals(fileClosingMonth)) {
            return lineDate;
        }
        return lineDate.minusMonths(number - 1L);
    }

    /**
     * Parcelamento compatível (§26): mesma quantidade de parcelas, a parcela {@code k} na mesma fatura, valor a menos
     * de {@code n} centavos e a parcela ainda não confirmada por arquivo. Desempate: compra mais antiga, depois o id.
     */
    public static Optional<UUID> match(Collection<Candidate> candidates, Set<InstallmentKey> confirmed, int number,
                                       int count, YearMonth closingMonth, Money amount) {
        BigDecimal tolerance = BigDecimal.valueOf(count).movePointLeft(2);
        return candidates.stream()
                .filter(candidate -> candidate.count() == count && candidate.number() == number)
                .filter(candidate -> candidate.closingMonth().equals(closingMonth))
                .filter(candidate -> candidate.amount().currency().equals(amount.currency()))
                .filter(candidate -> candidate.amount().amount().subtract(amount.amount()).abs()
                        .compareTo(tolerance) < 0)
                .filter(candidate -> !confirmed.contains(new InstallmentKey(candidate.transactionId(), number)))
                .min(Comparator.comparing(Candidate::purchasedOn)
                        .thenComparing(candidate -> candidate.transactionId().toString()))
                .map(Candidate::transactionId);
    }
}
