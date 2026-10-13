package com.smarthealthfinance.analytics.domain.enums;

/** Classificação de uma variação (ADR-0015 §3). Só descreve a direção: se é bom ou ruim depende do que se
 * compara (gasto subindo é ruim; receita subindo é boa) e fica com a UI. */
public enum Trend {

    /** Subiu 5% ou mais. */
    UP,
    /** Caiu 5% ou mais. */
    DOWN,
    /** Variação menor que 5% (ou zero nas duas pontas). */
    STABLE,
    /** Base zero e valor atual positivo. */
    NEW,
    /** Base positiva e valor atual zero. */
    GONE
}
