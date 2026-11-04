package com.smarthealthfinance.forecast.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;

/**
 * Ajuste do usuário numa série recorrente (ADR-0032 §2). Pelo menos um dos campos deve vir; o ausente mantém o valor
 * atual. O valor tem até 2 casas e é positivo; o dia vai de 1 a 31 (em meses mais curtos vale o último dia).
 *
 * @param expectedAmount valor esperado, na moeda do Workspace
 * @param expectedDay    dia do mês, 1 a 31
 */
public record ForecastAdjustmentRequest(MoneyDto expectedAmount, Integer expectedDay) {
}
