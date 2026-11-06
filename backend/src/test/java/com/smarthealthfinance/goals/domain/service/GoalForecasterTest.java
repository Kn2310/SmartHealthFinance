package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.goals.domain.enums.GoalConfidence;
import com.smarthealthfinance.goals.domain.enums.GoalForecastReason;
import com.smarthealthfinance.goals.domain.enums.GoalForecastStatus;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Previsão de conclusão (ADR-0034 §4) com os objetivos de 09-dados-de-exemplo; hoje = set/2026. */
class GoalForecasterTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final Money CAPACITY = money("2360.00");
    private static final Money PLANNED = money("1500.00");

    @Test
    void sampleGoals() {
        GoalForecast reserve = forecast("15152.00", "800.00", null, List.of());
        assertThat(reserve.status()).isEqualTo(GoalForecastStatus.NO_DEADLINE);
        assertThat(reserve.completion()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(reserve.contributionsLeft()).isEqualTo(19);

        GoalForecast lisbon = forecast("7700.00", "700.00", YearMonth.of(2027, 7), List.of(money("700.00")));
        assertThat(lisbon.status()).isEqualTo(GoalForecastStatus.LATE);
        assertThat(lisbon.completion()).isEqualTo(YearMonth.of(2027, 8));
        assertThat(lisbon.monthsLate()).isEqualTo(1);
        assertThat(lisbon.pessimistic()).isEqualTo(YearMonth.of(2027, 8));

        GoalForecast delivery = forecast("1800.00", "300.00", YearMonth.of(2027, 3), List.of());
        assertThat(delivery.status()).isEqualTo(GoalForecastStatus.ON_TRACK);
        assertThat(delivery.completion()).isEqualTo(YearMonth.of(2027, 3));
        assertThat(delivery.monthsEarly()).isZero();
    }

    @Test
    void slowerRealPaceWidensTheRangeAndLowersTheConfidence() {
        // Reserva com aportes reais de R$ 500 (21 set no D-Goals): pessimista pelo ritmo de 500.
        GoalForecast reserve = forecast("15152.00", "800.00", null,
                List.of(money("500.00"), money("500.00"), money("500.00")));

        assertThat(reserve.completion()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(reserve.pessimistic()).isEqualTo(YearMonth.of(2029, 4));
        assertThat(reserve.realPace()).isEqualTo(money("500.00"));
        assertThat(reserve.confidence()).isEqualTo(GoalConfidence.LOW);

        GoalForecast stopped = forecast("15152.00", "800.00", null, List.of(Money.zero(BRL)));
        assertThat(stopped.pessimistic()).isNull();
        assertThat(stopped.openEnded()).isTrue();
    }

    @Test
    void confidenceIsHighWithThreeMonthsOnPlanAndEnoughCapacity() {
        List<Money> onPlan = List.of(money("700.00"), money("800.00"), money("700.00"));

        assertThat(forecast("7700.00", "700.00", null, onPlan).confidence()).isEqualTo(GoalConfidence.HIGH);
        assertThat(forecast("7700.00", "700.00", null, onPlan.subList(0, 2)).confidence())
                .isEqualTo(GoalConfidence.MEDIUM);
        assertThat(GoalForecaster.forecast(GoalStatus.ACTIVE, money("7700.00"), false, money("700.00"), null,
                SEPTEMBER, true, onPlan, money("1000.00"), PLANNED).confidence()).isEqualTo(GoalConfidence.LOW);
    }

    @Test
    void noForecastWithoutForecastPlanOrActiveStatus() {
        assertThat(GoalForecaster.forecast(GoalStatus.ACTIVE, money("100.00"), false, money("10.00"), null, SEPTEMBER,
                false, List.of(), null, PLANNED).reason()).isEqualTo(GoalForecastReason.FORECAST_NOT_READY);
        assertThat(forecast("100.00", "0.00", null, List.of()).reason()).isEqualTo(GoalForecastReason.NO_PLAN);
        assertThat(GoalForecaster.forecast(GoalStatus.PAUSED, money("100.00"), false, money("10.00"), null, SEPTEMBER,
                true, List.of(), CAPACITY, PLANNED).reason()).isEqualTo(GoalForecastReason.NOT_ACTIVE);
        assertThat(forecast("1000000.00", "1.00", null, List.of()).reason()).isEqualTo(GoalForecastReason.TOO_FAR);
        assertThat(GoalForecaster.forecast(GoalStatus.ACTIVE, Money.zero(BRL), true, money("10.00"), null, SEPTEMBER,
                false, List.of(), null, PLANNED).status()).isEqualTo(GoalForecastStatus.REACHED);
    }

    @Test
    void medianOfAnEvenCountIsTheMeanOfTheMiddleTwo() {
        assertThat(GoalForecaster.median(List.of(money("100.00"), money("301.00")))).isEqualTo(money("200.50"));
        assertThat(GoalForecaster.median(List.of())).isNull();
    }

    private static GoalForecast forecast(String remaining, String planned, YearMonth deadline, List<Money> nets) {
        return GoalForecaster.forecast(GoalStatus.ACTIVE, money(remaining), false, money(planned), deadline,
                SEPTEMBER, true, nets, CAPACITY, PLANNED);
    }

    private static Money money(String amount) {
        return Money.of(amount, BRL);
    }
}
