package com.smarthealthfinance.cards.domain.model;

import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardLast4;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardNickname;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditCardTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    void createsActiveCardWithOnlyTheLastFourDigits() {
        CreditCard card = aurora();

        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.last4().value()).isEqualTo("4821");
        assertThat(card.last4().toString()).doesNotContain("4821");
        assertThat(card.id().value().version()).isEqualTo(7);
        assertThat(card.version()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"482", "48210", "4111111111111111", "48 1", "abcd", " 4821", "４８２１"})
    void last4AcceptsExactlyFourAsciiDigits(String value) {
        assertInvalidValue(() -> new CardLast4(value), "last4", "INVALID");
    }

    @Test
    void validatesNicknameAndLimit() {
        assertInvalidValue(() -> new CardLast4(""), "last4", "REQUIRED");
        assertInvalidValue(() -> new CardNickname(" "), "nickname", "REQUIRED");
        assertInvalidValue(() -> new CardNickname("x".repeat(101)), "nickname", "TOO_LONG");
        assertInvalidValue(() -> CardLimit.parse("0", "BRL", BRL), "limit", "NOT_POSITIVE");
        assertInvalidValue(() -> CardLimit.parse("-10", "BRL", BRL), "limit", "NOT_POSITIVE");
        assertInvalidValue(() -> CardLimit.parse("10.001", "BRL", BRL), "limit", "TOO_MANY_DECIMALS");
        assertInvalidValue(() -> CardLimit.parse("1e3", "BRL", BRL), "limit", "INVALID_FORMAT");
        assertInvalidValue(() -> CardLimit.parse("100", "USD", BRL), "limit", "CURRENCY");
        assertInvalidValue(() -> CardLimit.parse(null, "BRL", BRL), "limit", "REQUIRED");
        assertInvalidValue(() -> CardBrand.parse("DINERS"), "brand", "INVALID");
        assertThat(CardLimit.parse("12000.00", "BRL", BRL).value()).isEqualTo(Money.of("12000", BRL));
    }

    @Test
    void updateReportsWhetherSomethingChanged() {
        CreditCard card = aurora();

        assertThat(card.updateDetails(card.nickname(), null, card.brand(), card.last4(), card.limit(), card.cycle(),
                null, LATER)).isFalse();
        assertThat(card.updatedAt()).isEqualTo(NOW);

        assertThat(card.updateDetails(card.nickname(), null, card.brand(), card.last4(),
                new CardLimit(Money.of("15000", BRL)), new BillingCycle(25, 2), null, LATER)).isTrue();
        assertThat(card.cycle()).isEqualTo(new BillingCycle(25, 2));
        assertThat(card.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void archivedCardIsNotEditableButCanBeReactivated() {
        CreditCard card = aurora();

        assertThat(card.archive(LATER)).isTrue();
        assertThat(card.archive(LATER)).isFalse();
        assertThatThrownBy(() -> card.updateDetails(card.nickname(), null, card.brand(), card.last4(), card.limit(),
                card.cycle(), null, LATER)).isInstanceOf(CreditCardArchivedException.class);
        assertThat(card.reactivate(LATER)).isTrue();
        assertThat(card.reactivate(LATER)).isFalse();
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
    }

    static CreditCard aurora() {
        return CreditCard.create(CardId.generate(NOW), new WorkspaceId(UUID.randomUUID()),
                new CardNickname("Aurora Platinum"), null, CardBrand.MASTERCARD, new CardLast4("4821"),
                new CardLimit(Money.of("12000.00", BRL)), new BillingCycle(28, 5), null, NOW);
    }
}
