package com.smarthealthfinance.transactions.application.service;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository;
import com.smarthealthfinance.transactions.domain.service.SystemCategoryRules;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Categorização automática determinística (ADR-0013 §2): a regra aprendida do Workspace vence a do sistema, e nada
 * sobrescreve a escolha do usuário. Sem IA. {@link #forWorkspace} carrega o catálogo e as regras uma vez, para
 * categorizar um lote (importação, retroativo) sem uma consulta por transação.
 */
@Service
public class AutoCategorizer {

    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;

    public AutoCategorizer(CategoryRepository categories, CategoryRuleRepository rules) {
        this.categories = categories;
        this.rules = rules;
    }

    public Session forWorkspace(WorkspaceId workspaceId) {
        Map<CategoryCode, Category> catalog = categories.findAll().stream()
                .collect(Collectors.toMap(Category::code, category -> category));
        Map<String, CategoryCode> learned = new HashMap<>();
        rules.findAll(workspaceId).forEach(rule ->
                learned.put(ruleKey(rule.merchantKey(), rule.category().nature()), rule.category().code()));
        return new Session(catalog, learned);
    }

    private static String ruleKey(String merchantKey, CategoryNature nature) {
        return nature + ":" + merchantKey;
    }

    public static final class Session {

        private final Map<CategoryCode, Category> catalog;
        private final Map<String, CategoryCode> learned;

        private Session(Map<CategoryCode, Category> catalog, Map<String, CategoryCode> learned) {
            this.catalog = catalog;
            this.learned = learned;
        }

        public Optional<Category> category(CategoryCode code) {
            return Optional.ofNullable(catalog.get(code));
        }

        /**
         * Aplica a primeira regra que casa a uma transação que ainda não foi tratada.
         *
         * @return true se a transação mudou
         */
        public boolean apply(Transaction transaction, Instant now) {
            if (!transaction.awaitsAutomation()) {
                return false;
            }
            CategoryNature nature = CategoryNature.acceptedBy(transaction.type()).orElseThrow();

            Optional<Category> byLearnedRule = transaction.merchant()
                    .map(merchant -> learned.get(ruleKey(merchant.key(), nature)))
                    .flatMap(this::category);
            if (byLearnedRule.isPresent()) {
                return transaction.categorizeByRule(byLearnedRule.get(), CategoryOrigin.LEARNED, now);
            }

            return SystemCategoryRules.match(nature, transaction.description().value())
                    .flatMap(this::category)
                    .map(category -> transaction.categorizeByRule(category,
                            CategoryOrigin.system(SystemCategoryRules.VERSION), now))
                    .orElse(false);
        }
    }
}
