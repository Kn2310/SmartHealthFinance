package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Atribui ou remove a categoria de uma transação (spec 05.3, ADR-0012 §7). Idempotente: a mesma categoria não grava
 * nada. A categoria é hábito de consumo: o log carrega só os IDs.
 */
@Service
public class CategorizeTransaction {

    private static final Logger log = LoggerFactory.getLogger(CategorizeTransaction.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final Clock clock;

    public CategorizeTransaction(TransactionAccess access, TransactionRepository transactions,
                                 CategoryRepository categories, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.categories = categories;
        this.clock = clock;
    }

    /** @param categoryCode {@code null} volta a "Sem categoria" */
    public record Command(String categoryCode) {}

    @Transactional
    public TransactionView execute(UUID workspaceId, UUID transactionId, Command command) {
        Transaction transaction = access.requireTransaction(workspaceId, transactionId);
        TransactionCategory before = transaction.category().orElse(null);

        if (command.categoryCode() == null) {
            transaction.clearCategory(clock.instant());
        }
        else {
            Category category = categories.findByCode(new CategoryCode(command.categoryCode()))
                    .orElseThrow(() -> new InvalidValueException("categoryCode", "INVALID"));
            transaction.categorize(category, clock.instant());
        }

        if (!Objects.equals(before, transaction.category().orElse(null))) {
            transactions.save(transaction);
            log.info("Transaction categorized transactionId={} workspaceId={}", transaction.id(),
                    transaction.workspaceId());
        }

        return TransactionView.from(transaction);
    }
}
