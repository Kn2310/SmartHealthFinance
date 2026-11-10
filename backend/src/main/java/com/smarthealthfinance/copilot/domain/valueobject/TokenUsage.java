package com.smarthealthfinance.copilot.domain.valueobject;

/**
 * Tokens de uma chamada, como o provedor os cobra: {@code input} sem cache, {@code cacheRead} e {@code cacheWrite}
 * à parte; {@code output} inclui o raciocínio. Nunca negativos.
 */
public record TokenUsage(long input, long output, long cacheRead, long cacheWrite) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

    public TokenUsage {
        if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    /** Tamanho do prompt para a faixa de preço e para a quota (todas as entradas, inclusive cache). */
    public long promptTokens() {
        return input + cacheRead + cacheWrite;
    }
}
