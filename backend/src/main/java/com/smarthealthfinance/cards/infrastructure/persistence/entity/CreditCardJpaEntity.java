package com.smarthealthfinance.cards.infrastructure.persistence.entity;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.cards.domain.enums.CardBrand;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardLast4;
import com.smarthealthfinance.cards.domain.valueobject.CardLimit;
import com.smarthealthfinance.cards.domain.valueobject.CardNickname;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "credit_cards")
public class CreditCardJpaEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String nickname;

    @Column(name = "institution_name")
    private String institutionName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardBrand brand;

    @Column(nullable = false)
    private String last4;

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "closing_day", nullable = false)
    private short closingDay;

    @Column(name = "due_day", nullable = false)
    private short dueDay;

    @Column(name = "default_payment_account_id")
    private UUID defaultPaymentAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CreditCardJpaEntity() {
    }

    public static CreditCardJpaEntity from(CreditCard card) {
        CreditCardJpaEntity entity = new CreditCardJpaEntity();

        entity.id = card.id().value();
        entity.workspaceId = card.workspaceId().value();
        entity.nickname = card.nickname().value();
        entity.institutionName = card.institutionName().map(InstitutionName::value).orElse(null);
        entity.brand = card.brand();
        entity.last4 = card.last4().value();
        entity.creditLimit = card.limit().value().amount();
        entity.currency = card.limit().value().currency().getCurrencyCode();
        entity.closingDay = (short) card.cycle().closingDay();
        entity.dueDay = (short) card.cycle().dueDay();
        entity.defaultPaymentAccountId = card.defaultPaymentAccountId().map(AccountId::value).orElse(null);
        entity.status = card.status();
        entity.createdAt = card.createdAt();
        entity.updatedAt = card.updatedAt();
        entity.version = card.version();

        return entity;
    }

    public CreditCard toDomain() {
        return CreditCard.restore(new CardId(id), new WorkspaceId(workspaceId), new CardNickname(nickname),
                institutionName == null ? null : new InstitutionName(institutionName), brand, new CardLast4(last4),
                new CardLimit(new Money(creditLimit, Currency.getInstance(currency))),
                new BillingCycle(closingDay, dueDay),
                defaultPaymentAccountId == null ? null : new AccountId(defaultPaymentAccountId), status, createdAt,
                updatedAt, version);
    }
}
