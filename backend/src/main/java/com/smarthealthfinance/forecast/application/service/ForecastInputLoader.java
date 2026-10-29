package com.smarthealthfinance.forecast.application.service;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.model.FlowTransaction;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.cards.application.port.CardPortfolio;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastInput.InvoiceEventFact;
import com.smarthealthfinance.forecast.domain.service.RecurrenceDetector;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.model.AccountPosition;
import com.smarthealthfinance.overview.domain.model.CardDebts;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monta a entrada do cálculo do Forecast de um Workspace, só pelas portas (ADR-0031 §1, §11):
 * <ul>
 *   <li>o fluxo por transação ({@link AnalyticsReadModel#flowTransactions}) e o primeiro lançamento;</li>
 *   <li>o saldo total de hoje: as contas ativas, com o saldo de {@link OverviewReadModel#accountFigures}, somadas
 *   pela regra do Overview ({@link AccountPosition#totalOf});</li>
 *   <li>a posição de hoje dos cartões ativos ({@link CardPortfolio}, ADR-0027 §1);</li>
 *   <li>os fatos de fatura dos "Próximos eventos" (ADR-0006 §18), pelo próprio {@link CardDebts} do Overview;</li>
 *   <li>as séries persistidas, com o estado do usuário.</li>
 * </ul>
 * Nenhuma regra de fluxo, saldo ou fatura própria.
 */
@Service
public class ForecastInputLoader {

    /** Meses fechados anteriores ao corrente que o cálculo lê: a janela da detecção (ADR-0030 §2). */
    static final int LOOKBACK_MONTHS = RecurrenceDetector.HISTORY_MONTHS;

    private final WorkspaceRepository workspaces;
    private final AccountRepository accounts;
    private final AnalyticsReadModel analytics;
    private final OverviewReadModel overview;
    private final CardPortfolio cards;
    private final RecurringSeriesRepository series;
    private final Clock clock;
    private final ZoneId businessZone;

    public ForecastInputLoader(WorkspaceRepository workspaces, AccountRepository accounts,
                               AnalyticsReadModel analytics, OverviewReadModel overview, CardPortfolio cards,
                               RecurringSeriesRepository series,
                               Clock clock, @Value("${shf.overview.zone:America/Sao_Paulo}") ZoneId businessZone) {
        this.workspaces = workspaces;
        this.accounts = accounts;
        this.analytics = analytics;
        this.overview = overview;
        this.cards = cards;
        this.series = series;
        this.clock = clock;
        this.businessZone = businessZone;
    }

    /** Hoje no fuso de negócio (ADR-0006 §10). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(businessZone));
    }

    public ForecastInput load(WorkspaceId workspaceId) {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
        Currency currency = workspace.baseCurrency();
        LocalDate today = today();

        Optional<LocalDate> firstActivity = analytics.firstActivity(workspaceId);
        LocalDate from = YearMonth.from(today).minusMonths(LOOKBACK_MONTHS).atDay(1);
        List<ForecastEntry> entries = analytics.flowTransactions(workspaceId, new AnalyticsPeriod(from, today),
                currency).stream().map(ForecastInputLoader::entry).toList();

        List<CardPosition> positions = cards.positions(workspaceId, List.of(today)).getOrDefault(today, List.of());
        return new ForecastInput(today, currency, firstActivity, entries, series.findAll(workspaceId),
                totalBalance(workspaceId, today, currency), positions, invoiceEvents(positions, today, currency));
    }

    private Money totalBalance(WorkspaceId workspaceId, LocalDate today, Currency currency) {
        List<Account> active = accounts.findAllByWorkspace(workspaceId).stream()
                .filter(account -> account.status() == AccountStatus.ACTIVE).toList();
        if (active.isEmpty()) {
            return Money.zero(currency);
        }
        Map<AccountId, OverviewReadModel.AccountFigures> figures = overview.accountFigures(workspaceId,
                OverviewPeriod.resolve(PeriodType.CUSTOM, today, today, today), currency);
        List<AccountPosition> positions = active.stream()
                .map(account -> {
                    OverviewReadModel.AccountFigures figure = figures.get(account.id());
                    return new AccountPosition(account.id(), account.includedInTotal(),
                            figure == null ? Money.zero(currency) : figure.balance(),
                            figure == null ? 0 : figure.movementCount());
                })
                .toList();
        return AccountPosition.totalOf(positions, currency);
    }

    /**
     * Os eventos de fatura do ADR-0006 §18, já calculados pelo {@link CardDebts}: status {@code PARTIAL} (fatura
     * aberta: fechamento e vencimento) vira {@code INVOICE_PARTIAL}, {@code CLOSED} (fechada a vencer) vira
     * {@code INVOICE_OPEN} e {@code OVERDUE} vira {@code INVOICE_OVERDUE}.
     */
    private static List<InvoiceEventFact> invoiceEvents(List<CardPosition> positions, LocalDate today,
                                                        Currency currency) {
        CardDebts debts = CardDebts.of(positions, today, currency);
        if (debts == null) {
            return List.of();
        }
        List<InvoiceEventFact> facts = new ArrayList<>();
        for (CardDebts.UpcomingEvent event : debts.upcomingEvents()) {
            UpcomingEventKind kind = switch (event.status()) {
                case PARTIAL -> UpcomingEventKind.INVOICE_PARTIAL;
                case CLOSED -> UpcomingEventKind.INVOICE_OPEN;
                case OVERDUE -> UpcomingEventKind.INVOICE_OVERDUE;
            };
            YearMonth closingMonth = positions.stream()
                    .filter(position -> position.cardId().value().equals(event.card().id()))
                    .flatMap(position -> position.statements().stream())
                    .filter(statement -> statement.status() != InvoiceStatus.FUTURE
                            && statement.dates().dueMonth().equals(event.dueMonth()))
                    .map(statement -> statement.dates().closingMonth())
                    .findFirst().orElse(event.dueMonth());
            facts.add(new InvoiceEventFact(event.card().id(), event.card().nickname(), closingMonth, kind,
                    event.date(), event.amount()));
        }
        return facts;
    }

    private static ForecastEntry entry(FlowTransaction t) {
        Channel channel = t.accountId() != null ? Channel.account(t.accountId()) : Channel.card(t.cardId());
        return new ForecastEntry(t.transactionId(), t.day(), t.role(), t.categoryCode(), t.merchantKey(),
                t.merchantName(), t.amount(), channel, t.installment());
    }

}
