package com.smarthealthfinance.analytics.application.port;

import com.smarthealthfinance.analytics.domain.model.CategoryFlow;
import com.smarthealthfinance.analytics.domain.model.DailyFlow;
import com.smarthealthfinance.analytics.domain.model.MerchantFlow;
import com.smarthealthfinance.analytics.domain.model.MonthlyFlow;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.Currency;
import java.util.List;

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
}
