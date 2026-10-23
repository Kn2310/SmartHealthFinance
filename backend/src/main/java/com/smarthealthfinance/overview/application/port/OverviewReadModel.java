package com.smarthealthfinance.overview.application.port;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import com.smarthealthfinance.overview.domain.model.CashFlow;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

    /** Fluxo de caixa de contas ativas incluídas no total e de cartões ativos (ADR-0006 §17). */
    CashFlow cashFlow(WorkspaceId workspaceId, OverviewPeriod period, Currency currency);

    Activity activity(WorkspaceId workspaceId, OverviewPeriod period, Currency currency);

    /** POSTED e PENDING do período, mais recentes primeiro, de qualquer conta do Workspace. */
    List<RecentMovement> recentMovements(WorkspaceId workspaceId, OverviewPeriod period, int limit, Currency currency);

    /**
     * Origens dos lançamentos POSTED de contas ativas no período (ADR-0019): {@code MANUAL} ou o formato do extrato
     * ({@code CSV}, {@code OFX}). Usado nas "Fontes" do Health.
     */
    Set<DataSource> dataSources(WorkspaceId workspaceId, OverviewPeriod period);

    record AccountFigures(Money balance, int movementCount) {}

    /**
     * @param hasPostedTransactions existe ao menos uma transação POSTED no Workspace (em qualquer data)
     * @param transferCount         transferências lançadas no período, informativas (nem receita nem despesa)
     */
    record Activity(boolean hasPostedTransactions, int postedInPeriod, int pendingInPeriod, int transferCount,
                    Money transferVolume) {}

    /**
     * @param accountId nula em compra e estorno no cartão (ADR-0025 §2)
     * @param card      cartão da compra/estorno ou que recebe o pagamento de fatura; vazio nas demais
     */
    record RecentMovement(TransactionId id, TransactionType type, AdjustmentDirection adjustmentDirection,
                          AccountId accountId, Optional<AccountId> destinationAccountId, Money amount,
                          LocalDate occurredOn, String description, TransactionStatus status,
                          Optional<TransactionId> refundOfTransactionId, TransactionSource source,
                          Optional<CardRef> card) {}

    /** Só o necessário para exibir "Cartão Aurora •••• 4821" (ADR-0026 §10). */
    record CardRef(UUID id, String nickname, String last4) {}
}
