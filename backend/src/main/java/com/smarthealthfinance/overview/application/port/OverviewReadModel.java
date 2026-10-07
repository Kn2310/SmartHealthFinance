package com.smarthealthfinance.overview.application.port;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.domain.model.CashFlow;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas de leitura que alimentam o Overview (ADR-0006). Não é um repositório de agregado: devolve projeções.
 * Toda consulta é escopada por Workspace. A semântica de saldo e fluxo é a de
 * {@link com.smarthealthfinance.transactions.domain.model.Transaction#balanceEffectOn}; cada adapter precisa
 * respeitá-la (há teste de paridade).
 */
public interface OverviewReadModel {

    /**
     * Saldo derivado até {@code period.to()} e movimentações lançadas dentro do período, por conta.
     * Contas sem nenhuma transação lançada não aparecem no mapa.
     */
    Map<AccountId, AccountFigures> accountFigures(WorkspaceId workspaceId, OverviewPeriod period, Currency currency);

    /** Fluxo de caixa de contas ativas incluídas no total. */
    CashFlow cashFlow(WorkspaceId workspaceId, OverviewPeriod period, Currency currency);

    Activity activity(WorkspaceId workspaceId, OverviewPeriod period, Currency currency);

    /** POSTED e PENDING do período, mais recentes primeiro, de qualquer conta do Workspace. */
    List<RecentMovement> recentMovements(WorkspaceId workspaceId, OverviewPeriod period, int limit, Currency currency);

    record AccountFigures(Money balance, int movementCount) {}

    /**
     * @param hasPostedTransactions existe ao menos uma transação POSTED no Workspace (em qualquer data)
     * @param transferCount         transferências lançadas no período, informativas (nem receita nem despesa)
     */
    record Activity(boolean hasPostedTransactions, int postedInPeriod, int pendingInPeriod, int transferCount,
                    Money transferVolume) {}

    record RecentMovement(TransactionId id, TransactionType type, AdjustmentDirection adjustmentDirection,
                          AccountId accountId, Optional<AccountId> destinationAccountId, Money amount,
                          LocalDate occurredOn, String description, TransactionStatus status,
                          Optional<TransactionId> refundOfTransactionId) {}
}
