package com.smarthealthfinance.goals.application;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.forecast.application.port.ForecastReader;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fakes mínimos das portas de leitura que os Goals usam (sem Mockito no projeto): só os métodos chamados respondem;
 * os demais falham para o teste não depender deles sem querer.
 */
public final class PortFakes {

    private static final Currency BRL = Currency.getInstance("BRL");

    private PortFakes() {
    }

    /** Forecast sem snapshot ({@code NOT_BUILT}): a previsão dos objetivos é {@code NO_FORECAST}. */
    public static ForecastReader forecastWithoutSnapshot() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "find" -> Optional.empty();
            case "state" -> new ForecastReader.SnapshotState(false, null, false, null, false, 0);
            default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args)
                    : unsupported(method.getName());
        };
        return (ForecastReader) Proxy.newProxyInstance(ForecastReader.class.getClassLoader(),
                new Class<?>[] {ForecastReader.class}, handler);
    }

    /**
     * Analytics com o histórico de 09-dados-de-exemplo de abr a ago/2026: receitas 6.500, 6.500, 6.500, 6.800, 6.500
     * (média R$ 6.560,00) e despesas 4.020, 4.310, 3.980, 4.460, 4.190 (média R$ 4.192,00).
     */
    public static final class Analytics {

        private final List<FlowEntry> entries = new ArrayList<>();
        private LocalDate firstActivity = LocalDate.of(2026, 4, 1);

        public Analytics() {
            String[] income = {"6500.00", "6500.00", "6500.00", "6800.00", "6500.00"};
            String[] expense = {"4020.00", "4310.00", "3980.00", "4460.00", "4190.00"};
            for (int i = 0; i < income.length; i++) {
                LocalDate day = YearMonth.of(2026, 4 + i).atDay(5);
                entries.add(new FlowEntry(day, CashFlowRole.INCOME, "SALARY", null, null, Money.of(income[i], BRL), 1));
                entries.add(new FlowEntry(day, CashFlowRole.EXPENSE, "DINING_OUT", null, null,
                        Money.of(expense[i], BRL), 1));
            }
        }

        public void empty() {
            entries.clear();
            firstActivity = null;
        }

        public AnalyticsReadModel port() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "entries" -> {
                    AnalyticsPeriod period = (AnalyticsPeriod) args[1];
                    yield entries.stream()
                            .filter(e -> !e.day().isBefore(period.from()) && !e.day().isAfter(period.to()))
                            .toList();
                }
                case "firstActivity" -> Optional.ofNullable(firstActivity);
                case "categoryNames" -> Map.of("DINING_OUT", "Alimentação fora", "SALARY", "Salário");
                default -> unsupported(method.getName());
            };
            return (AnalyticsReadModel) Proxy.newProxyInstance(AnalyticsReadModel.class.getClassLoader(),
                    new Class<?>[] {AnalyticsReadModel.class}, handler);
        }
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException("fake sem " + method);
    }
}
