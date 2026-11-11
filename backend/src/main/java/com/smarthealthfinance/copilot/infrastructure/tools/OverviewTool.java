package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import com.smarthealthfinance.overview.application.dto.OverviewView;
import com.smarthealthfinance.overview.application.usecase.GetFinancialOverview;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * {@code OVERVIEW} (ADR-0039 §4) sobre {@link GetFinancialOverview}: saldo total, fluxo do período e saldo de cada
 * conta como {@code conta_N} com o tipo. Nome e instituição da conta e transações recentes (descrições cruas) nunca
 * entram no contexto (§3).
 */
@Component
public class OverviewTool implements FinancialTool {

    static final String SOURCE = "overview-v1";
    static final int MAX_ACCOUNTS = 5;

    private final GetFinancialOverview overview;

    public OverviewTool(GetFinancialOverview overview) {
        this.overview = overview;
    }

    @Override
    public Intent intent() {
        return Intent.OVERVIEW;
    }

    @Override
    public Result collect(Request request) {
        YearMonth current = YearMonth.from(request.today());
        YearMonth month = request.decision().monthValue().orElse(current);
        GetFinancialOverview.Query query = month.equals(current)
                ? new GetFinancialOverview.Query(null, null, null, 1)
                : new GetFinancialOverview.Query("CUSTOM", month.atDay(1), month.atEndOfMonth(), 1);
        OverviewView view = overview.execute(request.workspaceId(), query);
        if (!view.state().hasFinancialData()) {
            return Result.of(Status.NO_DATA);
        }
        String period = ToolValues.range(view.period().from(), view.period().to());
        ToolValues.Builder values = new ToolValues.Builder(SOURCE)
                .money("Saldo total das contas", view.summary().totalBalance(),
                        view.summary().balanceAsOf() == null ? null : view.summary().balanceAsOf().toString());
        if (view.cashFlow() != null) {
            values.money("Receitas no período", view.cashFlow().income(), period)
                    .money("Despesas no período", view.cashFlow().expense(), period)
                    .money("Estornos no período", view.cashFlow().refunds(), period)
                    .money("Resultado do período (receitas mais estornos menos despesas)", view.cashFlow().net(),
                            period);
        }
        values.count("Contas ativas", view.summary().accountCount(), null)
                .count("Transações pendentes no período, fora do saldo", view.summary().pendingTransactions(),
                        period);
        view.accounts().stream().limit(MAX_ACCOUNTS).forEach(account -> {
            String pseudonym = ToolValues.account(request.pseudonyms(), account.id(), account.type().name(),
                    account.name());
            values.money("Saldo de " + pseudonym + " (" + account.type() + ")", account.balance(), null);
        });
        LocalDate to = view.period().to();
        DataQuality quality = new DataQuality(null, to != null && !to.isBefore(request.today()), List.of(),
                DataFreshness.UP_TO_DATE, Set.of());
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("GetFinancialOverview", SOURCE)), List.of());
    }
}
