package com.smarthealthfinance.forecast.domain.enums;

/** Limitações de qualidade de dado de uma projeção (ADR-0030 §5). */
public enum Limitation {

    /** Menos de 3 meses fechados desde o primeiro lançamento: sem estimativa, só os fatos. */
    INSUFFICIENT_HISTORY,
    /** 3 a 5 meses fechados: confiança no máximo MEDIUM. */
    SHORT_HISTORY,
    /** Amplitude acima de 25% do valor central: confiança no máximo LOW. */
    HIGH_VARIABILITY,
    /** Mais de 15% da despesa sem categoria: a confiança desce um nível. */
    UNCATEGORIZED,
    /** Cartão com menos de 2 faturas fechadas acompanhadas: sem estimativa de novas compras. */
    PARTIAL_CARD_HISTORY
}
