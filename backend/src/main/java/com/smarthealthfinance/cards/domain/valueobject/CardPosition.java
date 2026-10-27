package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Situação de um cartão ativo numa data (ADR-0027 §1): o razão do ADR-0025 calculado com "hoje" = {@code asOf} e só
 * com os fatos até essa data. Limite e apelido são os atuais. Lido por Overview, Health e Insights pela porta
 * {@code CardPortfolio}.
 *
 * @param statements           faturas acompanhadas do razão, em ordem de vencimento (inclui a atual)
 * @param current              a fatura aberta em {@code asOf}
 * @param nextInvoiceCommitted parcelas já comprometidas na fatura seguinte à atual
 * @param commitments          parcelamentos com parcelas em faturas que ainda não começaram
 * @param dueInvoices          faturas com valor &gt; 0 cujo vencimento efetivo já passou (ou é hoje), com a pontualidade
 * @param lines                lançamentos das até 6 últimas faturas iniciadas, por mês de fechamento
 */
public record CardPosition(
        CardId cardId,
        String nickname,
        String last4,
        Money limit,
        LocalDate asOf,
        CardUtilization utilization,
        InvoiceStatement current,
        Money nextInvoiceCommitted,
        List<InvoiceStatement> statements,
        List<InstallmentCommitment> commitments,
        List<DueInvoice> dueInvoices,
        Map<YearMonth, List<InvoiceLine>> lines
) {
    public CardPosition {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(nickname, "nickname");
        Objects.requireNonNull(last4, "last4");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(utilization, "utilization");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(nextInvoiceCommitted, "nextInvoiceCommitted");
        statements = List.copyOf(statements);
        commitments = List.copyOf(commitments);
        dueInvoices = List.copyOf(dueInvoices);
        lines = Map.copyOf(lines);
    }

    /** Parcelamento com parcelas ainda não faturadas. Sem a descrição da compra (texto livre do usuário). */
    public record InstallmentCommitment(UUID transactionId, String merchantName, String categoryCode, int count,
                                        int remainingCount, Money remainingAmount, YearMonth lastDueMonth) {}

    /** Fatura vencida até {@code asOf}: paga em dia se o saldo era zero no vencimento efetivo. */
    public record DueInvoice(YearMonth dueMonth, LocalDate dueDate, LocalDate effectiveDueDate, Money amount,
                             boolean paidOnTime) {}

    /**
     * Lançamento de uma fatura com o valor que ficou nela: a parcela menos o estorno aplicado, ou o crédito que sobrou
     * (positivo; abate a fatura).
     *
     * @param installmentNumber e installmentCount nulos em créditos
     */
    public record InvoiceLine(InvoiceItemKind kind, UUID transactionId, Integer installmentNumber,
                              Integer installmentCount, String categoryCode, String merchantName, Money net) {}

    /** Faturas fechadas com saldo em aberto. */
    public List<InvoiceStatement> unpaidClosed() {
        return statements.stream()
                .filter(s -> s.status() != InvoiceStatus.FUTURE && s.status() != InvoiceStatus.OPEN)
                .filter(s -> s.remaining().isPositive())
                .toList();
    }

    public Optional<InvoiceStatement> statement(YearMonth closingMonth) {
        return statements.stream().filter(s -> s.dates().closingMonth().equals(closingMonth)).findFirst();
    }

    /** Parcelas em faturas que ainda não começaram (ADR-0025 §9). */
    public Money futureInstallments() {
        return utilization.futureInstallments();
    }
}
