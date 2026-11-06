package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.goals.domain.valueobject.GoalProgress;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Progresso como fato (ADR-0034 §2.2), com os valores de 09-dados-de-exemplo. */
class GoalProgressCalculatorTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @ParameterizedTest(name = "{0} de {1} → {2}%, faltam {3}")
    @CsvSource({
            // Viagem para Lisboa: R$ 4.300 de 12.000 → 36% (35,83 HALF_UP)
            "4300.00, 12000.00, 36, 7700.00, false",
            // Reserva de emergência: R$ 10.000 de 25.152 → 40% (39,76)
            "10000.00, 25152.00, 40, 15152.00, false",
            // Guardar a diferença do delivery, recém-criado
            "0.00, 1800.00, 0, 1800.00, false",
            // 0,5% arredonda para 1%
            "9.00, 1800.00, 1, 1791.00, false",
            // 99,6% não vira 100% antes de atingir o alvo
            "1793.00, 1800.00, 99, 7.00, false",
            // atingido exatamente e acima do alvo
            "1800.00, 1800.00, 100, 0.00, true",
            "2000.00, 1800.00, 100, 0.00, true"
    })
    void progressTable(String balance, String target, int percent, String remaining, boolean reached) {
        GoalProgress progress = GoalProgressCalculator.of(Money.of(target, BRL), Money.of(balance, BRL));

        assertThat(progress.saved()).isEqualTo(Money.of(balance, BRL));
        assertThat(progress.percent()).isEqualTo(percent);
        assertThat(progress.remaining()).isEqualTo(Money.of(remaining, BRL));
        assertThat(progress.reached()).isEqualTo(reached);
        assertThat(progress.destinationBalance()).isEqualTo(Money.of(balance, BRL));
    }

    @Test
    void negativeBalanceSavesNothingButKeepsTheRealBalance() {
        GoalProgress progress = GoalProgressCalculator.of(Money.of("1000.00", BRL), Money.of("-50.00", BRL));

        assertThat(progress.saved()).isEqualTo(Money.zero(BRL));
        assertThat(progress.percent()).isZero();
        assertThat(progress.remaining()).isEqualTo(Money.of("1000.00", BRL));
        assertThat(progress.reached()).isFalse();
        assertThat(progress.destinationBalance()).isEqualTo(Money.of("-50.00", BRL));
    }

    @Test
    void targetMustBePositive() {
        assertThatThrownBy(() -> GoalProgressCalculator.of(Money.zero(BRL), Money.zero(BRL)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
