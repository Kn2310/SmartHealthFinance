package com.smarthealthfinance.overview.application.usecase;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.dto.OverviewView;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.application.port.OverviewReadModel.AccountFigures;
import com.smarthealthfinance.overview.application.port.OverviewReadModel.Activity;
import com.smarthealthfinance.overview.application.port.OverviewReadModel.RecentMovement;
import com.smarthealthfinance.overview.domain.enums.MovementFlow;
import com.smarthealthfinance.overview.domain.enums.OverviewState;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.model.AccountPosition;
import com.smarthealthfinance.overview.domain.model.CashFlow;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Query GetFinancialOverview (spec 05.3, ADR-0006): consolida contas e transações em uma visão de leitura.
 * Não é dono de nenhum fato financeiro e não guarda nada.
 * <p>
 * Autorização no Workspace primeiro (ADR-0003). Sem dados (sem contas, sem transações lançadas) os valores
 * monetários ficam nulos e as consultas de saldo/fluxo nem rodam.
 */
@Service
public class GetFinancialOverview {

    public static final int DEFAULT_RECENT_LIMIT = 5;
    public static final int MAX_RECENT_LIMIT = 20;

    private static final Logger log = LoggerFactory.getLogger(GetFinancialOverview.class);

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final AccountRepository accounts;
    private final OverviewReadModel readModel;
    private final Clock clock;
    private final ZoneId businessZone;
    private final MeterRegistry meters;

    public GetFinancialOverview(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                                AccountRepository accounts, OverviewReadModel readModel, Clock clock,
                                @Value("${shf.overview.zone:America/Sao_Paulo}") ZoneId businessZone,
                                MeterRegistry meters) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.accounts = accounts;
        this.readModel = readModel;
        this.clock = clock;
        this.businessZone = businessZone;
        this.meters = meters;
    }

    /** {@code period} nulo assume CURRENT_MONTH; {@code from}/{@code to} só valem para CUSTOM. */
    public record Query(String period, LocalDate from, LocalDate to, Integer recentLimit) {}

    @Transactional(readOnly = true)
    public OverviewView execute(UUID workspaceId, Query query) {
        Timer.Sample sample = Timer.start(meters);

        Workspace workspace = accessGuard.loadAsMember(currentUser.requireCurrentUserId(), new WorkspaceId(workspaceId));
        WorkspaceId id = workspace.id();
        Currency currency = workspace.baseCurrency();

        OverviewPeriod period = OverviewPeriod.resolve(PeriodType.parseOrDefault(query.period()), query.from(),
                query.to(), LocalDate.now(clock.withZone(businessZone)));
        int recentLimit = recentLimit(query.recentLimit());

        List<Account> all = accounts.findAllByWorkspace(id);
        List<Account> active = all.stream().filter(account -> account.status() == AccountStatus.ACTIVE).toList();

        OverviewView view = active.isEmpty()
                ? build(id, period, OverviewState.NO_ACCOUNTS, active, all, currency, null, List.of(), null, null)
                : buildWithActivity(id, period, recentLimit, active, all, currency);

        sample.stop(Timer.builder("shf.overview.get").tag("state", view.state().name()).register(meters));
        log.info("Overview served workspaceId={} period={} state={}", id, period.type(), view.state());
        return view;
    }

    private OverviewView buildWithActivity(WorkspaceId id, OverviewPeriod period, int recentLimit,
                                           List<Account> active, List<Account> all, Currency currency) {
        Activity activity = readModel.activity(id, period, currency);
        OverviewState state = OverviewState.resolve(active.size(), activity.hasPostedTransactions(),
                activity.postedInPeriod());
        List<RecentMovement> recent = readModel.recentMovements(id, period, recentLimit, currency);

        if (!state.hasFinancialData()) {
            return build(id, period, state, active, all, currency, activity, recent, null, null);
        }
        return build(id, period, state, active, all, currency, activity, recent,
                readModel.accountFigures(id, period, currency), readModel.cashFlow(id, period, currency));
    }

    private static OverviewView build(WorkspaceId id, OverviewPeriod period, OverviewState state, List<Account> active,
                                      List<Account> all, Currency currency, Activity activity,
                                      List<RecentMovement> recent, Map<AccountId, AccountFigures> figures,
                                      CashFlow cashFlow) {
        List<AccountPosition> positions = figures == null ? List.of() : active.stream()
                .map(account -> position(account, figures.get(account.id()), currency))
                .toList();
        Map<AccountId, AccountPosition> positionById = positions.stream()
                .collect(Collectors.toMap(AccountPosition::accountId, Function.identity()));

        Money totalBalance = figures == null ? null : AccountPosition.totalOf(positions, currency);
        OverviewView.Summary summary = new OverviewView.Summary(totalBalance, period.balanceAsOf(), active.size(),
                activity == null ? 0 : activity.pendingInPeriod());

        OverviewView.CashFlowView cashFlowView = cashFlow == null ? null : new OverviewView.CashFlowView(
                cashFlow.income(), cashFlow.expense(), cashFlow.refunds(), cashFlow.net(), activity.transferCount(),
                activity.transferVolume());

        List<OverviewView.AccountSummary> accountSummaries = active.stream().map(account -> {
            AccountPosition position = positionById.get(account.id());
            return new OverviewView.AccountSummary(account.id().value(), account.name().value(), account.type(),
                    account.institutionName().map(InstitutionName::value).orElse(null), account.includedInTotal(),
                    position == null ? null : position.balance(), position == null ? 0 : position.movementCount());
        }).toList();

        Map<AccountId, Account> byId = all.stream().collect(Collectors.toMap(Account::id, Function.identity()));
        List<OverviewView.RecentTransaction> recentTransactions = recent.stream()
                .map(movement -> recentTransaction(movement, byId))
                .toList();

        return new OverviewView(id.value(), new OverviewView.Period(period.type(), period.from(), period.to()), state,
                summary, cashFlowView, accountSummaries, recentTransactions);
    }

    /** Conta ativa sem nenhuma transação lançada, num Workspace com dados, tem saldo zero real. */
    private static AccountPosition position(Account account, AccountFigures figures, Currency currency) {
        return new AccountPosition(account.id(), account.includedInTotal(),
                figures == null ? Money.zero(currency) : figures.balance(),
                figures == null ? 0 : figures.movementCount());
    }

    private static OverviewView.RecentTransaction recentTransaction(RecentMovement movement,
                                                                     Map<AccountId, Account> byId) {
        return new OverviewView.RecentTransaction(movement.id().value(), movement.type(),
                MovementFlow.of(movement.type(), movement.adjustmentDirection()), movement.adjustmentDirection(),
                movement.status(), movement.amount(), movement.occurredOn(), movement.description(),
                ref(byId.get(movement.accountId())),
                movement.destinationAccountId().map(byId::get).map(GetFinancialOverview::ref).orElse(null),
                movement.refundOfTransactionId().map(refund -> refund.value()).orElse(null), movement.source());
    }

    private static OverviewView.AccountRef ref(Account account) {
        return new OverviewView.AccountRef(account.id().value(), account.name().value());
    }

    private static int recentLimit(Integer requested) {
        int limit = requested == null ? DEFAULT_RECENT_LIMIT : requested;
        if (limit < 1 || limit > MAX_RECENT_LIMIT) {
            throw new InvalidValueException("recentLimit", "OUT_OF_RANGE");
        }
        return limit;
    }
}
