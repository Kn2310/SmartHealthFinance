package com.smarthealthfinance.forecast.application;

import com.smarthealthfinance.forecast.application.ForecastFakes.FakeStore;
import com.smarthealthfinance.forecast.application.ForecastFakes.InMemoryOutcomes;
import com.smarthealthfinance.forecast.application.ForecastFakes.InMemorySeries;
import com.smarthealthfinance.forecast.application.ForecastFakes.StubLoader;
import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore.Stored;
import com.smarthealthfinance.forecast.application.service.ForecastSnapshotCalculator;
import com.smarthealthfinance.forecast.application.service.ForecastSnapshotEvents;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.Periodicity;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recálculo do snapshot (ADR-0031 §3, §6): o estado do usuário nunca é sobrescrito, o mês que fecha é congelado uma
 * vez, o realizado é reconciliado até o dia 5 do mês seguinte e depois fica imutável. O recálculo é a função do
 * domínio sobre a entrada das portas (aqui fixada pelo teste).
 */
class ForecastSnapshotCalculatorTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final UUID ACCOUNT = UUID.nameUUIDFromBytes("account".getBytes(StandardCharsets.UTF_8));
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());

    private final FakeStore store = new FakeStore();
    private final InMemorySeries series = new InMemorySeries();
    private final InMemoryOutcomes outcomes = new InMemoryOutcomes();
    private final StubLoader loader = new StubLoader(series);
    private final List<IntegrationEvent> outbox = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ForecastSnapshotCalculator calculator = new ForecastSnapshotCalculator(loader, store, series,
            outcomes, Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC), meters,
            new ForecastSnapshotEvents(event -> {
                outbox.add(event);
                return UUID.randomUUID();
            }));

    // --- entradas ---

    private static ForecastEntry entry(String day, CashFlowRole role, String merchant, String amount) {
        return new ForecastEntry(UUID.nameUUIDFromBytes((day + merchant + amount + role).getBytes(
                StandardCharsets.UTF_8)), LocalDate.parse(day), role, "CAT", merchant, merchant.toUpperCase(),
                money(amount), Channel.account(ACCOUNT), false);
    }

    private static Money money(String amount) {
        return Money.of(amount, BRL);
    }

    /** Salário de 5.000 no dia 5 e aluguel de 1.000 no dia 15 (mar–jun), mais mercado variável (dias 8 e 20). */
    private static List<ForecastEntry> history() {
        List<ForecastEntry> entries = new ArrayList<>();
        for (String month : List.of("2026-03", "2026-04", "2026-05", "2026-06")) {
            entries.add(entry(month + "-05", CashFlowRole.INCOME, "empresa", "5000.00"));
            entries.add(entry(month + "-15", CashFlowRole.EXPENSE, "aluguel", "1000.00"));
            entries.add(entry(month + "-08", CashFlowRole.EXPENSE, "mercado", "150.00"));
            entries.add(entry(month + "-20", CashFlowRole.EXPENSE, "mercado", "150.00"));
        }
        return entries;
    }

    private void setInput(String today, List<ForecastEntry> entries) {
        LocalDate day = LocalDate.parse(today);
        loader.input = new ForecastInput(day, BRL, Optional.of(LocalDate.parse("2026-03-05")),
                entries.stream().filter(e -> !e.day().isAfter(day)).toList(), List.of(), money("10000.00"),
                List.of(), List.of());
    }

    private static RecurringSeries existing(SeriesDirection direction, String merchant, SeriesStatus status,
                                            String stale, Money userAmount, Integer userDay) {
        return new RecurringSeries(direction, "merchant:" + merchant, merchant.toUpperCase(),
                Channel.account(ACCOUNT), Periodicity.MONTHLY, 1, money(stale), new Range(money(stale), money(stale)),
                Confidence.LOW, List.of(UUID.randomUUID()), "forecast-v0", status, SeriesOrigin.DETECTED, userAmount,
                userDay, Instant.parse("2026-05-01T10:00:00Z"));
    }

    // --- estado do usuário ---

    @Test
    void recalculationNeverOverwritesTheUserStateAndOnlyRefreshesTheDetectedColumns() {
        series.put(WORKSPACE, existing(SeriesDirection.EXPENSE, "aluguel", SeriesStatus.CONFIRMED, "900.00",
                money("1234.00"), 20));
        series.put(WORKSPACE, existing(SeriesDirection.INCOME, "empresa", SeriesStatus.IGNORED, "4000.00", null,
                null));
        series.put(WORKSPACE, existing(SeriesDirection.EXPENSE, "academia", SeriesStatus.CONFIRMED, "100.00", null,
                null));
        setInput("2026-06-20", history());

        ForecastResult result = calculator.recalculate(WORKSPACE);

        RecurringSeries rent = series.find(WORKSPACE, SeriesDirection.EXPENSE, "merchant:aluguel").orElseThrow();
        assertThat(rent.expectedAmount()).isEqualTo(money("1000.00"));
        assertThat(rent.expectedDay()).isEqualTo(15);
        assertThat(rent.algorithmVersion()).isEqualTo("forecast-v1");
        assertThat(rent.status()).isEqualTo(SeriesStatus.CONFIRMED);
        assertThat(rent.origin()).isEqualTo(SeriesOrigin.DETECTED);
        assertThat(rent.userExpectedAmount()).isEqualTo(money("1234.00"));
        assertThat(rent.userExpectedDay()).isEqualTo(20);
        assertThat(rent.statusChangedAt()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));

        RecurringSeries salary = series.find(WORKSPACE, SeriesDirection.INCOME, "merchant:empresa").orElseThrow();
        assertThat(salary.status()).isEqualTo(SeriesStatus.IGNORED);
        assertThat(salary.expectedAmount()).isEqualTo(money("5000.00"));
        // uma chave que deixou de ser candidata continua na tabela, com o estado do usuário
        assertThat(series.find(WORKSPACE, SeriesDirection.EXPENSE, "merchant:academia")).isPresent()
                .get().extracting(RecurringSeries::status).isEqualTo(SeriesStatus.CONFIRMED);
        assertThat(series.findAll(WORKSPACE)).hasSize(3);
        assertThat(result.series()).hasSize(3);
    }

    @Test
    void theSnapshotIsTheDomainFunctionOverThePortsAndTheEventCarriesOnlyTheVersion() {
        setInput("2026-06-20", history());

        ForecastResult result = calculator.recalculate(WORKSPACE);

        assertThat(store.find(WORKSPACE, "forecast-v1")).contains(result);
        // a mesma entrada, sem séries persistidas, dá o mesmo resultado que o recálculo (série nova entra como DETECTED)
        assertThat(result).isEqualTo(ForecastCalculator.calculate(loader.input));
        assertThat(outbox).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("forecast.snapshot-updated");
            assertThat(event.eventVersion()).isEqualTo(1);
            assertThat(event.payload()).isEqualTo(java.util.Map.of("algorithmVersion", "forecast-v1"));
        });
    }

    // --- previsto x realizado ---

    @Test
    void monthTurnFreezesOnceReconcilesUntilDayFiveAndSettlesImmutably() {
        List<ForecastEntry> all = history();
        setInput("2026-06-20", all);
        ForecastResult june = calculator.recalculate(WORKSPACE);
        assertThat(outcomes.rows).isEmpty();

        // virada: o snapshot anterior (20/06) é de junho, então junho é congelado, com o realizado do momento
        setInput("2026-07-01", all);
        calculator.recalculate(WORKSPACE);
        Stored frozen = outcomes.rows.get(YearMonth.of(2026, 6));
        assertThat(frozen.outcome().projected().expense()).isEqualTo(june.month().expense().value());
        assertThat(frozen.outcome().projectedLow().expense()).isEqualTo(june.month().expense().low());
        assertThat(frozen.outcome().projectedHigh().expense()).isEqualTo(june.month().expense().high());
        assertThat(frozen.outcome().realized().expense()).isEqualTo(money("1300.00"));
        assertThat(frozen.settled()).isFalse();

        // importação atrasada até o dia 5: só o realizado e o erro mudam
        List<ForecastEntry> late = new ArrayList<>(all);
        late.add(entry("2026-06-25", CashFlowRole.EXPENSE, "farmacia", "400.00"));
        setInput("2026-07-04", late);
        calculator.recalculate(WORKSPACE);
        Stored reconciled = outcomes.rows.get(YearMonth.of(2026, 6));
        assertThat(reconciled.outcome().projected()).isEqualTo(frozen.outcome().projected());
        assertThat(reconciled.outcome().realized().expense()).isEqualTo(money("1700.00"));
        assertThat(reconciled.outcome().error().absolute().expense())
                .isEqualTo(money("1700.00").minus(frozen.outcome().projected().expense()));
        assertThat(reconciled.frozenAt()).isEqualTo(frozen.frozenAt());
        assertThat(reconciled.settled()).isFalse();

        // primeiro recálculo depois do dia 5: regrava uma última vez e liquida
        late.add(entry("2026-06-26", CashFlowRole.EXPENSE, "farmacia", "100.00"));
        setInput("2026-07-06", late);
        calculator.recalculate(WORKSPACE);
        Stored settled = outcomes.rows.get(YearMonth.of(2026, 6));
        assertThat(settled.settled()).isTrue();
        assertThat(settled.outcome().realized().expense()).isEqualTo(money("1800.00"));
        assertThat(meters.find("shf.forecast.month.error").tag("kind", "expense").summary()).isNotNull();

        // imutável depois: nem novas importações, nem novos recálculos mudam a linha
        late.add(entry("2026-06-28", CashFlowRole.EXPENSE, "farmacia", "999.00"));
        setInput("2026-07-20", late);
        calculator.recalculate(WORKSPACE);
        assertThat(outcomes.rows.get(YearMonth.of(2026, 6))).isEqualTo(settled);
        assertThat(outcomes.rows).hasSize(1);
    }

    @Test
    void aWorkspaceWithoutEnoughHistoryInTheClosedMonthRecordsNoOutcome() {
        // primeiro lançamento em mai/2026: em 20/06 há só 1 mês fechado (INSUFFICIENT_HISTORY)
        loader.input = new ForecastInput(LocalDate.parse("2026-06-20"), BRL,
                Optional.of(LocalDate.parse("2026-05-05")),
                List.of(entry("2026-05-05", CashFlowRole.INCOME, "empresa", "5000.00")), List.of(),
                money("100.00"), List.of(), List.of());
        ForecastResult first = calculator.recalculate(WORKSPACE);
        assertThat(first.limitations()).contains(Limitation.INSUFFICIENT_HISTORY);

        loader.input = new ForecastInput(LocalDate.parse("2026-07-01"), BRL,
                Optional.of(LocalDate.parse("2026-05-05")), loader.input.entries(), List.of(), money("100.00"),
                List.of(), List.of());
        calculator.recalculate(WORKSPACE);

        assertThat(outcomes.rows).isEmpty();
        assertThat(outcomes.freezeAttempts).isZero();
    }

    @Test
    void freezingTwiceKeepsTheFirstProjection() {
        setInput("2026-06-20", history());
        calculator.recalculate(WORKSPACE);
        setInput("2026-07-01", history());
        calculator.recalculate(WORKSPACE);
        Stored first = outcomes.rows.get(YearMonth.of(2026, 6));

        // um snapshot de junho reaparece (ex.: restauração) e o mês vira de novo: a primeira gravação vence
        store.snapshots.put(WORKSPACE + "/forecast-v1", ForecastCalculator.calculate(new ForecastInput(
                LocalDate.parse("2026-06-30"), BRL, Optional.of(LocalDate.parse("2026-03-05")),
                history().stream().filter(e -> !e.day().isAfter(LocalDate.parse("2026-06-30"))).toList(), List.of(),
                money("1.00"), List.of(), List.of())));
        setInput("2026-07-02", history());
        calculator.recalculate(WORKSPACE);

        assertThat(outcomes.rows.get(YearMonth.of(2026, 6)).outcome().projected())
                .isEqualTo(first.outcome().projected());
        assertThat(outcomes.freezeAttempts).isEqualTo(2);
    }
}
