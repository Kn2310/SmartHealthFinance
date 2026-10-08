package com.smarthealthfinance.ingestion.domain.valueobject;

import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DedupeKeyTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    @Test
    void externalIdKeyIsStableAndIgnoresAmountScale() {
        DedupeKey a = DedupeKey.ofExternalId("F1", DAY, Money.of("-86.4", BRL));
        DedupeKey b = DedupeKey.ofExternalId("F1", DAY, Money.of("-86.40", BRL));

        assertThat(a).isEqualTo(b);
        assertThat(a.value()).hasSize(64).matches("[0-9a-f]+");
    }

    /** Bancos que reutilizam FITID para lançamentos diferentes não perdem lançamentos. */
    @Test
    void reusedExternalIdWithOtherDateOrAmountIsAnotherKey() {
        DedupeKey original = DedupeKey.ofExternalId("F1", DAY, Money.of("-86.40", BRL));

        assertThat(DedupeKey.ofExternalId("F1", DAY.plusDays(1), Money.of("-86.40", BRL))).isNotEqualTo(original);
        assertThat(DedupeKey.ofExternalId("F1", DAY, Money.of("-86.41", BRL))).isNotEqualTo(original);
        assertThat(DedupeKey.ofExternalId("F1", DAY, Money.of("86.40", BRL))).isNotEqualTo(original);
    }

    @Test
    void fingerprintIgnoresAccentsCaseAndSpacing() {
        assertThat(DedupeKey.ofFingerprint(DAY, Money.of("-5", BRL), "Café  Lume", 1))
                .isEqualTo(DedupeKey.ofFingerprint(DAY, Money.of("-5.00", BRL), "CAFE lume", 1));
    }

    @Test
    void occurrenceSeparatesIdenticalLinesOfTheSameFile() {
        assertThat(DedupeKey.ofFingerprint(DAY, Money.of("-5", BRL), "Café", 1))
                .isNotEqualTo(DedupeKey.ofFingerprint(DAY, Money.of("-5", BRL), "Café", 2));
    }

    @Test
    void externalIdAndFingerprintNeverCollideByConstruction() {
        assertThat(DedupeKey.ofExternalId("1", DAY, Money.of("-5", BRL)))
                .isNotEqualTo(DedupeKey.ofFingerprint(DAY, Money.of("-5", BRL), "1", 1));
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new DedupeKey("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DedupeKey.ofFingerprint(DAY, Money.of("-5", BRL), "Café", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
