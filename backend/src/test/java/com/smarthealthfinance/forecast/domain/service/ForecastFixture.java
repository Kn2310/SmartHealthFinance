package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;
import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InvoiceLine;
import com.smarthealthfinance.cards.domain.valueobject.CardUtilization;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Periodicity;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastInput.InvoiceEventFact;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Montagem de entradas do Forecast para os testes. Tudo determinístico (ids derivados de um contador). */
final class ForecastFixture {

    static final Currency BRL = Currency.getInstance("BRL");
    static final UUID ACCOUNT = UUID.nameUUIDFromBytes("account".getBytes(StandardCharsets.UTF_8));
    static final UUID OTHER_ACCOUNT = UUID.nameUUIDFromBytes("other".getBytes(StandardCharsets.UTF_8));
    static final UUID AURORA = UUID.nameUUIDFromBytes("aurora".getBytes(StandardCharsets.UTF_8));

    private final List<ForecastEntry> entries = new ArrayList<>();
    private int sequence;

    static Money brl(String amount) {
        return Money.of(amount, BRL);
    }

    static LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    ForecastFixture add(String date, CashFlowRole role, String category, String merchant, String amount) {
        return add(date, role, category, merchant, amount, Channel.account(ACCOUNT), false);
    }

    ForecastFixture addCard(String date, String category, String merchant, String amount, UUID card,
                            boolean installment) {
        return add(date, CashFlowRole.EXPENSE, category, merchant, amount, Channel.card(card), installment);
    }

    ForecastFixture add(String date, CashFlowRole role, String category, String merchant, String amount,
                        Channel channel, boolean installment) {
        UUID id = UUID.nameUUIDFromBytes(("tx-" + sequence++).getBytes(StandardCharsets.UTF_8));
        entries.add(new ForecastEntry(id, LocalDate.parse(date), role, category, merchant,
                merchant == null ? null : merchant.toUpperCase(), brl(amount), channel, installment));
        return this;
    }

    /** A mesma transação em vários meses, no mesmo dia, com um valor por mês. */
    ForecastFixture monthly(CashFlowRole role, String merchant, int day, String category, String... monthAmounts) {
        for (String monthAmount : monthAmounts) {
            String[] parts = monthAmount.split("=");
            add(parts[0] + "-%02d".formatted(day), role, category, merchant, parts[1]);
        }
        return this;
    }

    List<ForecastEntry> entries() {
        return List.copyOf(entries);
    }

    ForecastInput input(String today, String firstActivity, String balance) {
        return input(today, firstActivity, balance, List.of(), List.of(), List.of());
    }

    ForecastInput input(String today, String firstActivity, String balance, List<RecurringSeries> existing,
                        List<CardPosition> cards, List<InvoiceEventFact> invoiceEvents) {
        return new ForecastInput(LocalDate.parse(today), BRL,
                firstActivity == null ? Optional.empty() : Optional.of(LocalDate.parse(firstActivity)), entries,
                existing, brl(balance), cards, invoiceEvents);
    }

    // ---- séries ----

    /** Série detectada (DETECTED/DETECTED), sem estado do usuário. */
    static RecurringSeries series(SeriesDirection direction, String merchantKey, int day, String amount,
                                  String min, String max, Confidence confidence, SeriesStatus status) {
        return new RecurringSeries(direction, "merchant:" + merchantKey, merchantKey.toUpperCase(),
                Channel.account(ACCOUNT), Periodicity.MONTHLY, day, brl(amount), new Range(brl(min), brl(max)),
                confidence, List.of(UUID.nameUUIDFromBytes(("occ-" + merchantKey).getBytes(StandardCharsets.UTF_8))),
                "forecast-v1", status, SeriesOrigin.DETECTED, null, null, null);
    }

    // ---- cartão ----

    static InvoiceDates dates(YearMonth closingMonth, int closingDay, int dueDay) {
        LocalDate closing = closingMonth.atDay(closingDay);
        YearMonth dueMonth = dueDay > closingDay ? closingMonth : closingMonth.plusMonths(1);
        return new InvoiceDates(closingMonth, dueMonth, closingMonth.minusMonths(1).atDay(closingDay), closing,
                dueMonth.atDay(dueDay), InvoiceDatesSource.CALCULATED);
    }

    static InvoiceStatement statement(YearMonth closingMonth, InvoiceStatus status, String amount, String remaining) {
        Money value = brl(amount);
        return new InvoiceStatement(dates(closingMonth, 28, 5), status, value, brl("0"), value,
                value.minus(brl(remaining)), brl(remaining));
    }

    static InvoiceLine charge(String amount) {
        return new InvoiceLine(InvoiceItemKind.CHARGE, UUID.randomUUID(), null, null, "GROCERIES", "Loja", brl(amount));
    }

    static InvoiceLine installment(int number, int count, String amount) {
        return new InvoiceLine(InvoiceItemKind.CHARGE, UUID.nameUUIDFromBytes("notebook".getBytes(
                StandardCharsets.UTF_8)), number, count, "ELECTRONICS", "Loja Tech", brl(amount));
    }

    static InvoiceLine credit(String amount) {
        return new InvoiceLine(InvoiceItemKind.CREDIT, UUID.randomUUID(), null, null, null, "Estorno", brl(amount));
    }

    /**
     * Aurora Platinum em 22/09/2026 (09-dados-de-exemplo): fecha dia 28 e vence dia 5; faturas fechadas de jun, jul e
     * ago (due jul, ago, set) de 2.710,40, 2.954,10 e 3.012,10 (as duas últimas com a parcela de 480 do notebook); a
     * atual (fecha 28/set, vence 05/out) com 3.412,80 parcial; as seguintes só com a parcela de 480 até mai/2027.
     */
    static CardPosition aurora() {
        List<InvoiceStatement> statements = new ArrayList<>();
        statements.add(statement(YearMonth.of(2026, 6), InvoiceStatus.PAID, "2710.40", "0"));
        statements.add(statement(YearMonth.of(2026, 7), InvoiceStatus.PAID, "2954.10", "0"));
        statements.add(statement(YearMonth.of(2026, 8), InvoiceStatus.PAID, "3012.10", "0"));
        InvoiceStatement current = statement(YearMonth.of(2026, 9), InvoiceStatus.OPEN, "3412.80", "3412.80");
        statements.add(current);
        for (YearMonth closing = YearMonth.of(2026, 10); !closing.isAfter(YearMonth.of(2027, 4));
             closing = closing.plusMonths(1)) {
            statements.add(statement(closing, InvoiceStatus.FUTURE, "480.00", "480.00"));
        }
        Map<YearMonth, List<InvoiceLine>> lines = Map.of(
                YearMonth.of(2026, 6), List.of(charge("2710.40")),
                YearMonth.of(2026, 7), List.of(charge("2474.10"), installment(1, 10, "480.00")),
                YearMonth.of(2026, 8), List.of(charge("2532.10"), installment(2, 10, "480.00")),
                YearMonth.of(2026, 9), List.of(charge("2932.80"), installment(3, 10, "480.00")));
        List<InvoiceDates> futureDates = new ArrayList<>();
        for (YearMonth closing = YearMonth.of(2026, 10); !closing.isAfter(YearMonth.of(2027, 3));
             closing = closing.plusMonths(1)) {
            futureDates.add(dates(closing, 28, 5));
        }
        Money zero = brl("0");
        CardUtilization utilization = new CardUtilization(brl("12000.00"), brl("6772.80"), brl("5227.20"), zero, 56, 28,
                zero, brl("3412.80"), brl("3360.00"));
        return new CardPosition(new CardId(AURORA), "Aurora Platinum", "4821", brl("12000.00"),
                LocalDate.parse("2026-09-22"), utilization, current, brl("480.00"), statements, List.of(), List.of(),
                lines, new BillingCycle(28, 5), futureDates);
    }
}
