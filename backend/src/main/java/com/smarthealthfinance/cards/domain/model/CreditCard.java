package com.smarthealthfinance.cards.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardLast4;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardNickname;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Cartão de crédito (ADR-0025 §1). Guarda só os 4 últimos dígitos: nunca número completo, CVV, validade ou senha.
 * Não tem saldo nem valor de fatura: tudo isso é derivado das transações.
 */
public class CreditCard {

    private final CardId id;
    private final WorkspaceId workspaceId;
    private CardNickname nickname;
    private InstitutionName institutionName;
    private CardBrand brand;
    private CardLast4 last4;
    private CardLimit limit;
    private BillingCycle cycle;
    private AccountId defaultPaymentAccountId;
    private CardStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private CreditCard(
            CardId id,
            WorkspaceId workspaceId,
            CardNickname nickname,
            InstitutionName institutionName,
            CardBrand brand,
            CardLast4 last4,
            CardLimit limit,
            BillingCycle cycle,
            AccountId defaultPaymentAccountId,
            CardStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.nickname = Objects.requireNonNull(nickname, "nickname");
        this.institutionName = institutionName;
        this.brand = Objects.requireNonNull(brand, "brand");
        this.last4 = Objects.requireNonNull(last4, "last4");
        this.limit = Objects.requireNonNull(limit, "limit");
        this.cycle = Objects.requireNonNull(cycle, "cycle");
        this.defaultPaymentAccountId = defaultPaymentAccountId;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** @param institutionName e defaultPaymentAccountId opcionais (nulos) */
    public static CreditCard create(
            CardId id,
            WorkspaceId workspaceId,
            CardNickname nickname,
            InstitutionName institutionName,
            CardBrand brand,
            CardLast4 last4,
            CardLimit limit,
            BillingCycle cycle,
            AccountId defaultPaymentAccountId,
            Instant now
    ) {
        return new CreditCard(id, workspaceId, nickname, institutionName, brand, last4, limit, cycle,
                defaultPaymentAccountId, CardStatus.ACTIVE, now, now, 0);
    }

    public static CreditCard restore(
            CardId id,
            WorkspaceId workspaceId,
            CardNickname nickname,
            InstitutionName institutionName,
            CardBrand brand,
            CardLast4 last4,
            CardLimit limit,
            BillingCycle cycle,
            AccountId defaultPaymentAccountId,
            CardStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new CreditCard(id, workspaceId, nickname, institutionName, brand, last4, limit, cycle,
                defaultPaymentAccountId, status, createdAt, updatedAt, version);
    }

    /**
     * Substitui todos os campos editáveis. Cartão arquivado não é editável.
     *
     * @return true se algum campo mudou
     */
    public boolean updateDetails(
            CardNickname newNickname,
            InstitutionName newInstitutionName,
            CardBrand newBrand,
            CardLast4 newLast4,
            CardLimit newLimit,
            BillingCycle newCycle,
            AccountId newDefaultPaymentAccountId,
            Instant now
    ) {
        Objects.requireNonNull(newNickname, "nickname");
        Objects.requireNonNull(newBrand, "brand");
        Objects.requireNonNull(newLast4, "last4");
        Objects.requireNonNull(newLimit, "limit");
        Objects.requireNonNull(newCycle, "cycle");
        ensureActive();

        if (nickname.equals(newNickname) && Objects.equals(institutionName, newInstitutionName) && brand == newBrand
                && last4.equals(newLast4) && limit.equals(newLimit) && cycle.equals(newCycle)
                && Objects.equals(defaultPaymentAccountId, newDefaultPaymentAccountId)) {
            return false;
        }

        nickname = newNickname;
        institutionName = newInstitutionName;
        brand = newBrand;
        last4 = newLast4;
        limit = newLimit;
        cycle = newCycle;
        defaultPaymentAccountId = newDefaultPaymentAccountId;
        updatedAt = now;
        return true;
    }

    /** @return true se o cartão estava ativo */
    public boolean archive(Instant now) {
        if (status == CardStatus.ARCHIVED) {
            return false;
        }
        status = CardStatus.ARCHIVED;
        updatedAt = now;
        return true;
    }

    /** @return true se o cartão estava arquivado */
    public boolean reactivate(Instant now) {
        if (status == CardStatus.ACTIVE) {
            return false;
        }
        status = CardStatus.ACTIVE;
        updatedAt = now;
        return true;
    }

    public void ensureActive() {
        if (status != CardStatus.ACTIVE) {
            throw new CreditCardArchivedException(id);
        }
    }

    public CardId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public CardNickname nickname() { return nickname; }
    public Optional<InstitutionName> institutionName() { return Optional.ofNullable(institutionName); }
    public CardBrand brand() { return brand; }
    public CardLast4 last4() { return last4; }
    public CardLimit limit() { return limit; }
    public BillingCycle cycle() { return cycle; }
    public Optional<AccountId> defaultPaymentAccountId() { return Optional.ofNullable(defaultPaymentAccountId); }
    public CardStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
