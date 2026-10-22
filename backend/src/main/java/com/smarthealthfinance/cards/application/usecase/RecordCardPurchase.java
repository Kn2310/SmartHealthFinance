package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardPurchaseView;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.InvoiceAssignments;
import com.smarthealthfinance.cards.domain.model.CardInstallment;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInstallmentRepository;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction.CardCommand;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction.CardOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compra no cartão, à vista ou parcelada (ADR-0025 §2/§6, ADR-0026 §10): uma {@code EXPENSE} com o valor total na data
 * da compra, criada pelo caso de uso de Transactions, e as parcelas nas faturas, na mesma transação de banco.
 * Idempotente pela {@code Idempotency-Key}; o número de parcelas faz parte do conteúdo da chave.
 */
@Service
public class RecordCardPurchase {

    private static final Logger log = LoggerFactory.getLogger(RecordCardPurchase.class);

    private final CardAccess access;
    private final CreateTransaction createTransaction;
    private final InvoiceAssignments assignments;
    private final CardInstallmentRepository installments;
    private final CardInvoiceRepository invoices;

    public RecordCardPurchase(CardAccess access, CreateTransaction createTransaction, InvoiceAssignments assignments,
                              CardInstallmentRepository installments, CardInvoiceRepository invoices) {
        this.access = access;
        this.createTransaction = createTransaction;
        this.assignments = assignments;
        this.installments = installments;
        this.invoices = invoices;
    }

    /** @param installmentCount nulo = à vista (1 parcela) */
    public record Command(String idempotencyKey, String amount, String currency, LocalDate occurredOn,
                          String description, Integer installmentCount) {}

    @Transactional
    public CardPurchaseView execute(UUID workspaceId, UUID cardId, Command command) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        card.ensureActive();

        int count = command.installmentCount() == null ? 1 : command.installmentCount();
        if (count < 1 || count > InstallmentSplitter.MAX_INSTALLMENTS) {
            throw new InvalidValueException("installmentCount", "OUT_OF_RANGE");
        }

        CreateTransaction.Result result = createTransaction.executeOnCard(workspaceId, new CardCommand(
                command.idempotencyKey(), CardOperation.PURCHASE, cardId, null, command.amount(), command.currency(),
                command.occurredOn(), command.description(), null, "installments=" + count));
        UUID transactionId = result.transaction().id();

        List<CardInstallment> parts;
        if (result.replayed()) {
            parts = installments.findByTransaction(card.workspaceId(), transactionId);
        }
        else {
            // O valor já foi validado pela transação (positivo, casas da moeda); faltam as parcelas.
            Money total = new Money(result.transaction().amount(), Currency.getInstance(result.transaction().currency()));
            parts = assignments.assignPurchase(card, transactionId, result.transaction().occurredOn(),
                    InstallmentSplitter.split(total, count));
            log.info("Card purchase recorded cardId={} transactionId={} workspaceId={} installments={}", card.id(),
                    transactionId, card.workspaceId(), count);
        }

        Map<CardInvoiceId, YearMonth> dueMonths = new HashMap<>();
        invoices.findByCard(card.workspaceId(), card.id())
                .forEach(row -> dueMonths.put(row.id(), dueMonthOf(row)));
        return new CardPurchaseView(result.transaction(), parts.stream()
                .map(part -> new CardPurchaseView.Installment(part.number(), part.amount(),
                        dueMonths.get(part.invoiceId())))
                .toList(), InvoiceCalendar.RULES_VERSION, result.replayed());
    }

    private static YearMonth dueMonthOf(CardInvoice row) {
        return row.dates().dueMonth();
    }
}
