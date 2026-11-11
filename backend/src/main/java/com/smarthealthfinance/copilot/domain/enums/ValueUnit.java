package com.smarthealthfinance.copilot.domain.enums;

/**
 * Unidade de um valor do contexto, que define o formato textual no contexto e a renderização pt-BR (ADR-0039 §5, §7):
 * <ul>
 *   <li>{@code MONEY}: decimal simples ({@code "1234.56"}), com a moeda no valor;</li>
 *   <li>{@code PERCENT}: decimal simples em pontos percentuais ({@code "12.5"} = 12,5%), na escala do backend;</li>
 *   <li>{@code SCORE} e {@code COUNT}: inteiro;</li>
 *   <li>{@code MONTH}: {@code YYYY-MM}; {@code DATE}: {@code YYYY-MM-DD};</li>
 *   <li>{@code TEXT}: nome de categoria do catálogo ou de comerciante normalizado, sempre tratado como dado.</li>
 * </ul>
 */
public enum ValueUnit {
    MONEY,
    PERCENT,
    SCORE,
    COUNT,
    MONTH,
    DATE,
    TEXT
}
