package com.smarthealthfinance.transactions.application.port;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;

import java.util.Currency;
import java.util.Objects;

/**
 * Resumo do período filtrado da lista de transações (ADR-0016): os mesmos filtros da listagem, sobre a fonte da
 * verdade (consistência imediata, como a lista).
 * <ul>
 *   <li>Valores só de POSTED, com a semântica do ADR-0006 §9 ({@code Transaction.cashFlowRole()}): receitas, despesas
 *   brutas, reembolsos em linha própria e transferências só informativas; ajuste fora.</li>
 *   <li>Sem filtro de conta: receitas, despesas e reembolsos só de contas ativas incluídas no total (como o Overview);
 *   transferências de todas as contas.</li>
 *   <li>Com filtro de conta: receitas, despesas e reembolsos daquela conta (mesmo fora do total ou arquivada) e as
 *   transferências que a envolvem.</li>
 *   <li>O filtro de status não se aplica aos valores; {@code pendingCount} conta as pendentes com os demais
 *   filtros.</li>
 * </ul>
 */
public interface TransactionSummaryReader {

    TransactionSummary summarize(WorkspaceId workspaceId, TransactionCriteria criteria, Currency currency);

    /**
     * @param postedCount  transações POSTED que passam nos filtros (de qualquer conta)
     * @param pendingCount transações PENDING que passam nos filtros
     */
    record TransactionSummary(Money income, Money expense, Money refunds, int transferCount, Money transferVolume,
                              int postedCount, int pendingCount) {

        public TransactionSummary {
            Objects.requireNonNull(income, "income");
            Objects.requireNonNull(expense, "expense");
            Objects.requireNonNull(refunds, "refunds");
            Objects.requireNonNull(transferVolume, "transferVolume");
        }

        /** Resultado: receitas + reembolsos − despesas (ADR-0006 §9). */
        public Money net() {
            return income.plus(refunds).minus(expense);
        }
    }
}
