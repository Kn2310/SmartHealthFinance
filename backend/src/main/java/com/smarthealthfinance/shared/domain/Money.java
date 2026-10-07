package com.smarthealthfinance.shared.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Valor monetário exato (specs 05.4/05.13): BigDecimal com a precisão do NUMERIC(19,4) + moeda.
 * Nunca usa ponto flutuante e nunca arredonda: precisão acima da suportada é rejeitada.
 * O sinal é permitido (saldos podem ser negativos); regras de "valor positivo" pertencem a quem usa.
 */
public record Money(BigDecimal amount, Currency currency) {

    /** Casas decimais armazenadas (NUMERIC(19,4)). */
    public static final int SCALE = 4;

    /** Dígitos inteiros suportados por NUMERIC(19,4). */
    public static final int MAX_INTEGER_DIGITS = 15;

    /** Decimal simples: sem expoente, sinal "+", separador de milhar ou vírgula. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        BigDecimal significant = amount.stripTrailingZeros();
        if (significant.scale() > SCALE) {
            throw new InvalidValueException("amount", "TOO_MANY_DECIMALS");
        }
        if (significant.precision() - significant.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidValueException("amount", "TOO_LARGE");
        }

        // Escala fixa: valores equivalentes ("86.4" e "86.40") são iguais em equals/hashCode.
        amount = amount.setScale(SCALE);
    }

    /** Interpreta o valor textual da API (ex.: {@code "86.40"}). */
    public static Money of(String amount, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        if (amount == null || amount.isBlank()) {
            throw new InvalidValueException("amount", "REQUIRED");
        }
        if (!PLAIN_DECIMAL.matcher(amount).matches()) {
            throw new InvalidValueException("amount", "INVALID_FORMAT");
        }
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** Casas decimais significativas (86.40 → 1; 100.00 → 0). */
    public int decimalPlaces() {
        return Math.max(0, amount.stripTrailingZeros().scale());
    }

    /** Representação da API: ao menos as casas da moeda (BRL → 2), sem zeros extras além disso. */
    public String toPlainString() {
        int scale = Math.max(currency.getDefaultFractionDigits(), decimalPlaces());
        return amount.setScale(scale).toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Moedas diferentes: " + currency + " e " + other.currency);
        }
    }
}
