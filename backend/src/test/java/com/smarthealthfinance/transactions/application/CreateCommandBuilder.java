package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.domain.model.Transaction;

import java.time.LocalDate;
import java.util.UUID;

import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;

/** Comando válido por padrão (despesa de R$ 86,40 em 22/09); cada teste altera só o que importa. */
public final class CreateCommandBuilder {

    private String idempotencyKey = UUID.randomUUID().toString();
    private String type = "EXPENSE";
    private UUID accountId;
    private UUID destinationAccountId;
    private String adjustmentDirection;
    private String amount = "86.40";
    private String currency = "BRL";
    private LocalDate occurredOn = SEP_22;
    private String description = "Bistrô Lume";
    private String status;
    private UUID refundOfTransactionId;

    private CreateCommandBuilder(UUID accountId) {
        this.accountId = accountId;
    }

    public static CreateCommandBuilder expense(Account account) {
        return new CreateCommandBuilder(account.id().value());
    }

    public static CreateCommandBuilder transfer(Account from, Account to) {
        return expense(from).type("TRANSFER").destination(to.id().value()).description("Transferência para Reserva");
    }

    public static CreateCommandBuilder refundOf(Transaction original, Account account) {
        return expense(account).type("REFUND").refundOf(original.id().value()).description("Estorno");
    }

    public CreateCommandBuilder key(String value) { idempotencyKey = value; return this; }
    public CreateCommandBuilder type(String value) { type = value; return this; }
    public CreateCommandBuilder account(UUID value) { accountId = value; return this; }
    public CreateCommandBuilder destination(UUID value) { destinationAccountId = value; return this; }
    public CreateCommandBuilder direction(String value) { adjustmentDirection = value; return this; }
    public CreateCommandBuilder amount(String value) { amount = value; return this; }
    public CreateCommandBuilder currency(String value) { currency = value; return this; }
    public CreateCommandBuilder occurredOn(LocalDate value) { occurredOn = value; return this; }
    public CreateCommandBuilder description(String value) { description = value; return this; }
    public CreateCommandBuilder status(String value) { status = value; return this; }
    public CreateCommandBuilder refundOf(UUID value) { refundOfTransactionId = value; return this; }

    public CreateTransaction.Command build() {
        return new CreateTransaction.Command(idempotencyKey, type, accountId, destinationAccountId, adjustmentDirection,
                amount, currency, occurredOn, description, status, refundOfTransactionId);
    }
}
