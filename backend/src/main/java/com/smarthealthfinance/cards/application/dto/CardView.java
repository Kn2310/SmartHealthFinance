package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.shared.domain.Money;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * @param trackingStartDueMonth mês de vencimento da primeira fatura acompanhada (ADR-0025 §15), como a API identifica
 *                              as faturas
 */
public record CardView(
        UUID id, UUID workspaceId, String nickname, String institutionName, CardBrand brand, String last4, Money limit,
        int closingDay, int dueDay, UUID defaultPaymentAccountId, CardStatus status, YearMonth trackingStartDueMonth,
        Instant createdAt, Instant updatedAt
) {
    public static CardView from(CreditCard card) {
        return new CardView(card.id().value(), card.workspaceId().value(), card.nickname().value(),
                card.institutionName().map(InstitutionName::value).orElse(null), card.brand(), card.last4().value(),
                card.limit().value(), card.cycle().closingDay(), card.cycle().dueDay(),
                card.defaultPaymentAccountId().map(AccountId::value).orElse(null), card.status(),
                new InvoiceCalendar(card.cycle(), List.of()).datesOf(card.trackingStartMonth()).dueMonth(),
                card.createdAt(), card.updatedAt());
    }
}
