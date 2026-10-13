package com.smarthealthfinance.analytics.domain.enums;

/** Base de comparação escolhida pelo usuário ("Comparar com", ADR-0015 §2). */
public enum ComparisonBase {

    /** O mesmo recorte de dias do mês anterior. */
    PREVIOUS,
    /** A média dos meses anteriores, no mesmo recorte de dias. */
    AVERAGE
}
