package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Lançamento lido de um extrato, ainda sem virar transação (ADR-0009).
 * <p>
 * O valor mantém o sinal do extrato (positivo = entrada, negativo = saída); o tipo da transação é derivado dele.
 * A descrição é normalizada, não rejeitada: espaços colapsados, controles removidos e corte no limite de
 * {@code TransactionDescription}. Descrição e valor são dados financeiros e nunca vão para logs.
 *
 * @param lineNumber posição no arquivo (CSV: linha física; OFX: ordem do STMTTRN)
 * @param externalId identificador do banco (FITID / coluna id), ou {@code null}
 */
public record StatementLine(int lineNumber, LocalDate occurredOn, Money signedAmount, String description,
                            String externalId) {

    /** Mesmo limite de {@code TransactionDescription}: a descrição importada precisa caber na transação. */
    public static final int MAX_DESCRIPTION_LENGTH = 200;

    public static final int MAX_EXTERNAL_ID_LENGTH = 255;

    private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}]+");

    public StatementLine {
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(signedAmount, "signedAmount");
        Objects.requireNonNull(description, "description");
    }

    /**
     * Valida e normaliza uma linha lida do arquivo.
     *
     * @throws InvalidValueException com o código da linha inválida (ex.: {@code ZERO_AMOUNT})
     */
    public static StatementLine of(int lineNumber, LocalDate occurredOn, Money signedAmount, String rawDescription,
                                   String rawExternalId) {
        if (occurredOn == null) {
            throw new InvalidValueException("occurredOn", "INVALID_DATE");
        }
        Objects.requireNonNull(signedAmount, "signedAmount");
        if (signedAmount.amount().signum() == 0) {
            throw new InvalidValueException("amount", "ZERO_AMOUNT");
        }
        if (signedAmount.decimalPlaces() > signedAmount.currency().getDefaultFractionDigits()) {
            throw new InvalidValueException("amount", "TOO_MANY_DECIMALS");
        }

        return new StatementLine(lineNumber, occurredOn, signedAmount, normalizeDescription(rawDescription),
                normalizeExternalId(rawExternalId));
    }

    public boolean isInflow() {
        return signedAmount.isPositive();
    }

    /** Valor positivo da transação (05.4: valor positivo + tipo). */
    public Money absoluteAmount() {
        return isInflow() ? signedAmount : signedAmount.negate();
    }

    private static String normalizeDescription(String raw) {
        String collapsed = raw == null ? "" : WHITESPACE_OR_CONTROL.matcher(raw).replaceAll(" ").strip();
        if (collapsed.isEmpty()) {
            throw new InvalidValueException("description", "DESCRIPTION_REQUIRED");
        }
        if (collapsed.length() <= MAX_DESCRIPTION_LENGTH) {
            return collapsed;
        }
        // Corte sem partir um par surrogate (emoji etc.).
        int end = MAX_DESCRIPTION_LENGTH;
        if (Character.isHighSurrogate(collapsed.charAt(end - 1))) {
            end--;
        }
        return collapsed.substring(0, end).strip();
    }

    private static String normalizeExternalId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > MAX_EXTERNAL_ID_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidValueException("externalId", "INVALID_EXTERNAL_ID");
        }
        return value;
    }
}
