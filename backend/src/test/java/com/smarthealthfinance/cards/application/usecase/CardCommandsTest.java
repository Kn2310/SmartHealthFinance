package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.exception.CardNotFoundException;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smarthealthfinance.cards.application.CardsTestContext.BRL;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardCommandsTest {

    private final CardsTestContext ctx = new CardsTestContext();

    @Test
    void createsCardAndRecordsEventWithOnlyItsId() {
        Account checking = ctx.account(ctx.anasWorkspace, "Banco Aurora");

        CardView view = ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command("Aurora Platinum",
                "Banco Aurora", "MASTERCARD", "4821", "12000.00", "BRL", 28, 5, checking.id().value()));

        assertThat(view.id().version()).isEqualTo(7);
        assertThat(view.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(view.last4()).isEqualTo("4821");
        assertThat(view.limit()).isEqualTo(Money.of("12000", BRL));
        assertThat(view.defaultPaymentAccountId()).isEqualTo(checking.id().value());
        assertThat(ctx.cards.size()).isEqualTo(1);
        assertThat(ctx.outbox).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(CardEvents.CREATED);
            assertThat(event.payload()).isEqualTo(Map.of("cardId", view.id().toString()));
        });
    }

    @Test
    void nonMemberCannotCreateAndNothingIsPersisted() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.cards.size()).isZero();
        assertThat(ctx.outbox).isEmpty();
    }

    @Test
    void invalidInputIsNotPersisted() {
        assertInvalidValue(() -> ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command("Aurora", null,
                "MASTERCARD", "4111111111111111", "12000.00", "BRL", 28, 5, null)), "last4", "INVALID");
        assertInvalidValue(() -> ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command("Aurora", null,
                "MASTERCARD", "4821", "12000.00", "BRL", 28, 28, null)), "dueDay", "SAME_AS_CLOSING");

        assertThat(ctx.cards.size()).isZero();
        assertThat(ctx.outbox).isEmpty();
    }

    @Test
    void defaultPaymentAccountMustBeActiveAndFromTheSameWorkspace() {
        Account bobs = ctx.account(ctx.bobsWorkspace, "Conta do Bob");
        Account archived = ctx.account(ctx.anasWorkspace, "Antiga");
        archived.archive(CardsTestContext.NOW);
        ctx.accounts.store(archived);

        assertThatThrownBy(() -> ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command("Aurora", null,
                "VISA", "4821", "1000", "BRL", 28, 5, bobs.id().value())))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command("Aurora", null,
                "VISA", "4821", "1000", "BRL", 28, 5, archived.id().value())))
                .isInstanceOf(AccountArchivedException.class);
        assertThat(ctx.cards.size()).isZero();
    }

    @Test
    void cardOfAnotherWorkspaceIsIndistinguishableFromMissing() {
        CardView anas = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> ctx.archiveCard.execute(ctx.bobsWorkspaceId(), anas.id()))
                .isInstanceOf(CardNotFoundException.class);
        assertThatThrownBy(() -> ctx.utilization.execute(ctx.bobsWorkspaceId(), anas.id()))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void updateWithoutChangesRecordsNoEvent() {
        CardView card = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        ctx.outbox.clear();

        ctx.updateCard.execute(ctx.anasWorkspaceId(), card.id(), new UpdateCard.Command("Aurora Platinum",
                "Banco Aurora", "MASTERCARD", "4821", "12000.00", "BRL", 28, 5, null));

        assertThat(ctx.outbox).isEmpty();
    }

    @Test
    void changingTheCycleRecalculatesOnlyInvoicesThatAreNotClosed() {
        CardView view = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        CreditCard card = ctx.storedCard(view.id());
        CardInvoice september = ctx.invoiceWith(card, YearMonth.of(2026, 8), "3012.10");
        CardInvoice october = ctx.invoiceWith(card, YearMonth.of(2026, 9), "3412.80");
        ctx.outbox.clear();

        ctx.updateCard.execute(ctx.anasWorkspaceId(), view.id(), new UpdateCard.Command("Aurora Platinum",
                "Banco Aurora", "MASTERCARD", "4821", "15000.00", "BRL", 25, 2, null));

        assertThat(ctx.invoices.get(september.id()).dates()).isEqualTo(september.dates());
        assertThat(ctx.invoices.get(october.id()).dates().closingDate()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(ctx.invoices.get(october.id()).dates().dueDate()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(ctx.outbox).extracting(IntegrationEvent::eventType).containsExactly(CardEvents.UPDATED);
    }

    @Test
    void changingTheCycleMovesInstallmentsOfOpenInvoicesButNeverIntoClosedOnes() {
        CardView view = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        CreditCard card = ctx.storedCard(view.id());
        UUID purchase = UUID.randomUUID();
        // Compra em 26 set, 3×: parcelas nas faturas que fecham em set, out e nov (fecha 28).
        ctx.purchase(card, purchase, LocalDate.parse("2026-09-26"), "Geladeira",
                List.of(Money.of("100.00", BRL), Money.of("100.00", BRL), Money.of("100.00", BRL)));
        CardInvoice august = ctx.invoiceWith(card, YearMonth.of(2026, 8), "50.00");

        // Fecha 25: 26 set passa para a fatura que fecha em 25 out; a de setembro fica vazia e sai.
        ctx.updateCard.execute(ctx.anasWorkspaceId(), view.id(), new UpdateCard.Command("Aurora Platinum",
                "Banco Aurora", "MASTERCARD", "4821", "12000.00", "BRL", 25, 2, null));

        List<YearMonth> months = ctx.installments.findByTransaction(card.workspaceId(), purchase).stream()
                .map(installment -> ctx.invoices.get(installment.invoiceId()).dates().closingMonth())
                .toList();
        assertThat(months).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12));
        assertThat(ctx.invoices.findByCard(card.workspaceId(), card.id()))
                .extracting(invoice -> invoice.dates().closingMonth())
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 10), YearMonth.of(2026, 11),
                        YearMonth.of(2026, 12));
        assertThat(ctx.invoices.get(august.id()).dates()).isEqualTo(august.dates());
    }

    @Test
    void cycleThatWouldCollideWithAClosedInvoiceIsRefusedWithoutChanges() {
        CardView view = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        CreditCard card = ctx.storedCard(view.id());
        ctx.invoiceWith(card, YearMonth.of(2026, 8), "3012.10");
        CardInvoice october = ctx.invoiceWith(card, YearMonth.of(2026, 9), "3412.80");
        ctx.outbox.clear();

        assertInvalidValue(() -> ctx.updateCard.execute(ctx.anasWorkspaceId(), view.id(), new UpdateCard.Command(
                "Aurora Platinum", null, "MASTERCARD", "4821", "12000.00", "BRL", 20, 30, null)),
                "dueDay", "DUE_MONTH_CONFLICT");

        assertThat(ctx.storedCard(view.id()).cycle().closingDay()).isEqualTo(28);
        assertThat(ctx.invoices.get(october.id()).dates()).isEqualTo(october.dates());
        assertThat(ctx.outbox).isEmpty();
    }

    @Test
    void archiveAndReactivateAreIdempotent() {
        CardView card = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        ctx.outbox.clear();

        ctx.archiveCard.execute(ctx.anasWorkspaceId(), card.id());
        ctx.archiveCard.execute(ctx.anasWorkspaceId(), card.id());
        assertThatThrownBy(() -> ctx.updateCard.execute(ctx.anasWorkspaceId(), card.id(), new UpdateCard.Command(
                "Outro nome", null, "MASTERCARD", "4821", "12000.00", "BRL", 28, 5, null)))
                .isInstanceOf(CreditCardArchivedException.class);
        assertThat(ctx.listCards.execute(ctx.anasWorkspaceId(), false)).isEmpty();
        assertThat(ctx.listCards.execute(ctx.anasWorkspaceId(), true)).hasSize(1);

        ctx.reactivateCard.execute(ctx.anasWorkspaceId(), card.id());
        ctx.reactivateCard.execute(ctx.anasWorkspaceId(), card.id());

        assertThat(ctx.outbox).extracting(IntegrationEvent::eventType)
                .containsExactly(CardEvents.ARCHIVED, CardEvents.REACTIVATED);
    }
}
