package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.RecentContributionView;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** "Aportes recentes" do D-Goals (ADR-0035): aportes (fato) dos objetivos abertos, mais recentes primeiro. */
public record RecentContributionsResponse(List<RecentContributionItem> contributions) {

    /** @param sourceAccountId a conta de onde veio o aporte */
    public record RecentContributionItem(UUID goalId, UUID transactionId, LocalDate occurredOn, MoneyDto amount, UUID sourceAccountId) {}

    public static RecentContributionsResponse from(List<RecentContributionView> views) {
        return new RecentContributionsResponse(views.stream()
                .map(view -> new RecentContributionItem(view.goalId(), view.contribution().transactionId().value(),
                        view.contribution().occurredOn(), GoalResponse.money(view.contribution().amount()),
                        view.contribution().counterpart().map(AccountId::value).orElse(null)))
                .toList());
    }
}
