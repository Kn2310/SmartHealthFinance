package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.shared.domain.Money;

import java.time.Instant;
import java.util.UUID;

public record CardView(
        UUID id, UUID workspaceId, String nickname, String institutionName, CardBrand brand, String last4, Money limit,
        int closingDay, int dueDay, UUID defaultPaymentAccountId, CardStatus status, Instant createdAt,
        Instant updatedAt
) {
    public static CardView from(CreditCard card) {
        return new CardView(card.id().value(), card.workspaceId().value(), card.nickname().value(),
                card.institutionName().map(InstitutionName::value).orElse(null), card.brand(), card.last4().value(),
                card.limit().value(), card.cycle().closingDay(), card.cycle().dueDay(),
                card.defaultPaymentAccountId().map(AccountId::value).orElse(null), card.status(), card.createdAt(),
                card.updatedAt());
    }
}
