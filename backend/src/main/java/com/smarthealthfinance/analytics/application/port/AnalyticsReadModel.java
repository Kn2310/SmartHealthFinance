package com.smarthealthfinance.analytics.application.port;

import com.smarthealthfinance.analytics.domain.model.CategoryFlow;
import com.smarthealthfinance.analytics.domain.model.DailyFlow;
import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.model.MerchantFlow;
import com.smarthealthfinance.analytics.domain.model.MonthlyFlow;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Leitura da projeção de Analytics (ADR-0014 §5). Sempre por Workspace; dinheiro em NUMERIC/BigDecimal.
 * Receitas, despesas e reembolsos consideram só contas ativas incluídas no total (ADR-0006 §9), aplicado na leitura.
 */
public interface AnalyticsReadModel {

    /** Fluxo de cada mês do intervalo que tem movimento; meses sem movimento não aparecem. */
    List<MonthlyFlow> monthly(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency);

    List<CategoryFlow> byCategory(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency);

    List<MerchantFlow> byMerchant(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency);

    /** Receitas, despesas e reembolsos por dia (sem transferências); dias sem movimento não aparecem. */
    List<DailyFlow> daily(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency);

    ProjectionFreshness freshness(WorkspaceId workspaceId);

    /**
     * Linhas do fluxo de caixa (INCOME, EXPENSE, REFUND de contas ativas incluídas no total) por dia, papel,
     * categoria e comerciante: o insumo dos cálculos da Análise (ADR-0015 §1).
     */
    List<FlowEntry> entries(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency);

    /** Nome de exibição de cada categoria do catálogo (ADR-0012 §3), por código; usado nos textos do Health. */
    Map<String, String> categoryNames();

    /** Primeiro dia com movimento no fluxo de caixa do Workspace; vazio se nenhum. */
    Optional<LocalDate> firstActivity(WorkspaceId workspaceId);

    /**
     * Maiores despesas individuais do intervalo (ADR-0015 §6), lidas de {@code transactions}: valor desc, data desc,
     * id. Mesma regra de contas do fluxo de caixa.
     */
    List<ExpenseItem> largestExpenses(WorkspaceId workspaceId, AnalyticsPeriod period, int limit, Currency currency);
}
