package com.smarthealthfinance.health.application.service;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Monta as entradas do cálculo de todos os meses de referência de um Workspace (ADR-0018 §4), só pelas portas:
 * uma leitura das linhas do fluxo de caixa ({@link AnalyticsReadModel#entries}) cobrindo todos os meses, o primeiro
 * lançamento, o catálogo de categorias, as contas {@code SAVINGS} ativas hoje e o saldo delas
 * ({@link OverviewReadModel#accountFigures}) na data-base de cada mês. Nenhuma regra de fluxo ou saldo própria.
 */
@Service
public class HealthInputLoader {

    /** Mês corrente + os 12 fechados anteriores (gravados) + 1 para a variação do mais antigo. */
    public static final int STORED_CLOSED_MONTHS = 12;
    /** Janela (6 meses) e média do Spending (5 meses) do mês mais antigo calculado. */
    private static final int LOOKBACK_MONTHS = 6;

    private final WorkspaceRepository workspaces;
    private final AccountRepository accounts;
    private final AnalyticsReadModel analytics;
    private final OverviewReadModel overview;
    private final Clock clock;
    private final ZoneId businessZone;

    public HealthInputLoader(WorkspaceRepository workspaces, AccountRepository accounts, AnalyticsReadModel analytics,
                             OverviewReadModel overview, Clock clock,
                             @Value("${shf.overview.zone:America/Sao_Paulo}") ZoneId businessZone) {
        this.workspaces = workspaces;
        this.accounts = accounts;
        this.analytics = analytics;
        this.overview = overview;
        this.clock = clock;
        this.businessZone = businessZone;
    }

    /** Hoje no fuso de negócio (ADR-0006 §10). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(businessZone));
    }

    /**
     * @return uma entrada por mês, do mais antigo ao corrente: os 12 fechados anteriores ao corrente, mais um antes
     *         deles (só para a variação), limitados ao mês do primeiro lançamento; vazia sem nenhum lançamento
     */
    public List<HealthInput> load(WorkspaceId workspaceId) {
        Workspace workspace = workspaces.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException());
        Currency currency = workspace.baseCurrency();
        LocalDate today = today();
        YearMonth current = YearMonth.from(today);

        Optional<LocalDate> firstActivity = analytics.firstActivity(workspaceId);
        if (firstActivity.isEmpty()) {
            return List.of();
        }
        YearMonth first = YearMonth.from(firstActivity.get());

        List<YearMonth> months = new ArrayList<>();
        for (int back = STORED_CLOSED_MONTHS + 1; back >= 0; back--) {
            YearMonth month = current.minusMonths(back);
            if (!month.isBefore(first)) {
                months.add(month);
            }
        }

        LocalDate from = months.getFirst().minusMonths(LOOKBACK_MONTHS).atDay(1);
        List<FlowEntry> entries = analytics.entries(workspaceId, new AnalyticsPeriod(from, today), currency);
        FlowLedger ledger = new FlowLedger(entries, currency);
        Map<String, String> names = analytics.categoryNames();

        List<Account> all = accounts.findAllByWorkspace(workspaceId);
        List<Account> active = all.stream().filter(a -> a.status() == AccountStatus.ACTIVE).toList();
        List<Account> reserves = active.stream().filter(a -> a.type() == AccountType.SAVINGS).toList();

        List<HealthInput> inputs = new ArrayList<>();
        for (YearMonth month : months) {
            AnalysisPeriod period = AnalysisPeriod.resolve(month.toString(), today);
            inputs.add(new HealthInput(period, firstActivity, ledger,
                    reserveBalances(workspaceId, reserves, period.to(), currency), active.size(), names));
        }
        return inputs;
    }

    private List<HealthInput.ReserveBalance> reserveBalances(WorkspaceId workspaceId, List<Account> reserves,
                                                              LocalDate asOf, Currency currency) {
        if (reserves.isEmpty()) {
            return List.of();
        }
        Map<AccountId, OverviewReadModel.AccountFigures> figures = overview.accountFigures(workspaceId,
                OverviewPeriod.resolve(PeriodType.CUSTOM, asOf, asOf, asOf), currency);
        return reserves.stream()
                .map(account -> new HealthInput.ReserveBalance(account.id().value(),
                        Optional.ofNullable(figures.get(account.id()))
                                .map(OverviewReadModel.AccountFigures::balance)
                                .orElse(Money.zero(currency))))
                .toList();
    }
}
