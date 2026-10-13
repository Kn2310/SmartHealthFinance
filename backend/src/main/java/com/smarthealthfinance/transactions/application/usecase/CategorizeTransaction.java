package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import com.smarthealthfinance.transactions.domain.valueobject.Merchant;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Atribui ou remove a categoria de uma transação (spec 05.3, ADR-0012 §7). Idempotente: a mesma escolha não grava
 * nada. A escolha é sempre de origem MANUAL e nunca é sobrescrita por regra (ADR-0013 §3). Com
 * {@code applyToSimilar}, a escolha também vira uma regra aprendida do Workspace para o comerciante e é aplicada às
 * transações dele que o usuário ainda não escolheu (ADR-0013 §2). A categoria é hábito de consumo: o log carrega
 * só os IDs.
 */
@Service
public class CategorizeTransaction {

    private static final Logger log = LoggerFactory.getLogger(CategorizeTransaction.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final Clock clock;

    public CategorizeTransaction(TransactionAccess access, TransactionRepository transactions,
                                 CategoryRepository categories, CategoryRuleRepository rules, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.categories = categories;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * @param categoryCode   {@code null} volta a "Sem categoria"
     * @param applyToSimilar aprender a regra do comerciante e aplicá-la às transações semelhantes; exige categoria
     *                       e comerciante
     */
    public record Command(String categoryCode, boolean applyToSimilar) {
        public Command(String categoryCode) {
            this(categoryCode, false);
        }
    }

    @Transactional
    public TransactionView execute(UUID workspaceId, UUID transactionId, Command command) {
        Transaction transaction = access.requireTransaction(workspaceId, transactionId);
        Instant now = clock.instant();
        Category category = command.categoryCode() == null ? null : findCategory(command.categoryCode());

        Merchant merchant = null;
        if (command.applyToSimilar()) {
            if (category == null) {
                throw new InvalidValueException("applyToSimilar", "REQUIRES_CATEGORY");
            }
            merchant = transaction.merchant().orElseThrow(() -> new InvalidValueException("applyToSimilar",
                    "NO_MERCHANT"));
        }

        TransactionCategory before = transaction.category().orElse(null);
        CategoryOrigin originBefore = transaction.categoryOrigin().orElse(null);

        if (category == null) {
            transaction.clearCategory(now);
        }
        else {
            transaction.categorize(category, now);
        }

        if (!Objects.equals(before, transaction.category().orElse(null))
                || !Objects.equals(originBefore, transaction.categoryOrigin().orElse(null))) {
            transactions.save(transaction);
            log.info("Transaction categorized transactionId={} workspaceId={}", transaction.id(),
                    transaction.workspaceId());
        }

        if (merchant != null) {
            applyToSimilar(transaction, category, merchant, now);
        }

        return TransactionView.from(transaction);
    }

    private Category findCategory(String code) {
        return categories.findByCode(new CategoryCode(code))
                .orElseThrow(() -> new InvalidValueException("categoryCode", "INVALID"));
    }

    private void applyToSimilar(Transaction chosen, Category category, Merchant merchant, Instant now) {
        WorkspaceId workspace = chosen.workspaceId();
        rules.upsert(workspace, new CategoryRuleRepository.LearnedRule(merchant.key(),
                category.asTransactionCategory()), now);

        int updated = 0;
        for (Transaction similar : transactions.findAutomatableByMerchant(workspace, merchant.key(),
                category.nature())) {
            if (!similar.id().equals(chosen.id()) && similar.categorizeByRule(category, CategoryOrigin.LEARNED, now)) {
                transactions.save(similar);
                updated++;
            }
        }

        log.info("Category rule learned transactionId={} workspaceId={} similarUpdated={}", chosen.id(), workspace,
                updated);
    }
}
