package com.smarthealthfinance.ingestion.domain.valueobject;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.ingestion.domain.enums.StatementKind;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Para onde vão as linhas de um batch: uma conta (ADR-0009 §4) ou um cartão (§19), escolhido no upload. O cartão é
 * conhecido aqui só pelo id; quem valida e lança é Cards.
 */
public record ImportDestination(StatementKind kind, UUID id) {

    public ImportDestination {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public static ImportDestination account(AccountId accountId) {
        return new ImportDestination(StatementKind.ACCOUNT, accountId.value());
    }

    public static ImportDestination card(UUID cardId) {
        return new ImportDestination(StatementKind.CARD, cardId);
    }

    public boolean isCard() {
        return kind == StatementKind.CARD;
    }

    public Optional<AccountId> accountId() {
        return isCard() ? Optional.empty() : Optional.of(new AccountId(id));
    }

    public Optional<UUID> cardId() {
        return isCard() ? Optional.of(id) : Optional.empty();
    }

    /** @throws IllegalStateException num batch de cartão */
    public AccountId requireAccount() {
        return accountId().orElseThrow(() -> new IllegalStateException("O destino é um cartão"));
    }

    @Override
    public String toString() {
        return kind + ":" + id;
    }
}
