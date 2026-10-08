package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Currency;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class StatementLineTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Test
    void negativeAmountIsAnOutflowWithPositiveTransactionAmount() {
        StatementLine line = StatementLine.of(2, DAY, Money.of("-86.40", BRL), "Bistrô Lume", "abc-1");

        assertThat(line.isInflow()).isFalse();
        assertThat(line.absoluteAmount()).isEqualTo(Money.of("86.40", BRL));
        assertThat(line.signedAmount()).isEqualTo(Money.of("-86.40", BRL));
        assertThat(line.externalId()).isEqualTo("abc-1");
    }

    @Test
    void positiveAmountIsAnInflow() {
        StatementLine line = StatementLine.of(2, DAY, Money.of("4500.00", BRL), "Salário", null);

        assertThat(line.isInflow()).isTrue();
        assertThat(line.absoluteAmount()).isEqualTo(Money.of("4500", BRL));
    }

    @Test
    void rejectsZeroAmount() {
        assertInvalidValue(() -> StatementLine.of(2, DAY, Money.of("0.00", BRL), "Tarifa", null),
                "amount", "ZERO_AMOUNT");
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyNeverRounding() {
        assertInvalidValue(() -> StatementLine.of(2, DAY, Money.of("-10.005", BRL), "Café", null),
                "amount", "TOO_MANY_DECIMALS");
    }

    @Test
    void rejectsMissingDate() {
        assertInvalidValue(() -> StatementLine.of(2, null, Money.of("-1", BRL), "Café", null),
                "occurredOn", "INVALID_DATE");
    }

    @Test
    void collapsesWhitespaceAndControlCharactersInDescription() {
        StatementLine line = StatementLine.of(2, DAY, Money.of("-1", BRL), "  PIX\tENVIADO \n  Mercado\u0007Sol ",
                null);

        assertThat(line.description()).isEqualTo("PIX ENVIADO Mercado Sol");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t\n" })
    void rejectsBlankDescription(String description) {
        assertInvalidValue(() -> StatementLine.of(2, DAY, Money.of("-1", BRL), description, null),
                "description", "DESCRIPTION_REQUIRED");
    }

    @Test
    void truncatesLongDescriptionInsteadOfRejecting() {
        StatementLine line = StatementLine.of(2, DAY, Money.of("-1", BRL), "a".repeat(250), null);

        assertThat(line.description()).hasSize(StatementLine.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String description = "a".repeat(StatementLine.MAX_DESCRIPTION_LENGTH - 1) + "😀" + "b";

        StatementLine line = StatementLine.of(2, DAY, Money.of("-1", BRL), description, null);

        assertThat(line.description()).isEqualTo("a".repeat(StatementLine.MAX_DESCRIPTION_LENGTH - 1));
    }

    @Test
    void blankExternalIdBecomesNull() {
        assertThat(StatementLine.of(2, DAY, Money.of("-1", BRL), "Café", "  ").externalId()).isNull();
    }

    @Test
    void rejectsExternalIdAboveLimit() {
        assertInvalidValue(() -> StatementLine.of(2, DAY, Money.of("-1", BRL), "Café", "x".repeat(256)),
                "externalId", "INVALID_EXTERNAL_ID");
    }
}
