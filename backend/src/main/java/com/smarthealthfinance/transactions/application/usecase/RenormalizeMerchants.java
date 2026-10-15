package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.service.TransactionEvents;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.service.MerchantNormalizer;
import com.smarthealthfinance.transactions.domain.valueobject.Merchant;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renormalização dos comerciantes para a versão atual do {@link MerchantNormalizer} (adendo de 2026-10-14 ao
 * ADR-0013 §1). Para cada transação com comerciante de outra versão:
 * <ul>
 *   <li>recalcula comerciante (chave, nome e versão) a partir da descrição; a categoria não muda;</li>
 *   <li>copia a regra aprendida da chave antiga para a nova (se as duas têm regra, vence a mais recente) e apaga a
 *   antiga quando nenhuma transação a usa mais;</li>
 *   <li>emite {@code transaction.categorized} (o comerciante mudou — ADR-0014 §2), para os consumidores recalcularem
 *   os meses afetados.</li>
 * </ul>
 * Cada Workspace roda na própria transação de banco. Idempotente: rodar de novo não encontra nada desatualizado.
 */
@Service
public class RenormalizeMerchants {

    private static final Logger log = LoggerFactory.getLogger(RenormalizeMerchants.class);

    private static final int BATCH_SIZE = 500;

    private final TransactionRepository transactions;
    private final CategoryRuleRepository rules;
    private final TransactionEvents events;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RenormalizeMerchants(TransactionRepository transactions, CategoryRuleRepository rules,
                                TransactionEvents events, TransactionTemplate transactionTemplate, Clock clock) {
        this.transactions = transactions;
        this.rules = rules;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * @param workspaces   Workspaces renormalizados
     * @param transactions transações cujo comerciante foi recalculado
     * @param keysChanged  pares (chave antiga → nova, natureza) distintos em que a chave mudou
     */
    public record Result(int workspaces, int transactions, int keysChanged) {
    }

    public Result execute() {
        List<WorkspaceId> targets = transactionTemplate.execute(status ->
                transactions.workspacesWithOutdatedMerchants(MerchantNormalizer.VERSION));
        int total = 0;
        int keys = 0;
        for (WorkspaceId workspace : targets == null ? List.<WorkspaceId>of() : targets) {
            Result result = transactionTemplate.execute(status -> renormalize(workspace));
            total += result.transactions();
            keys += result.keysChanged();
        }
        int workspaces = targets == null ? 0 : targets.size();
        log.info("Merchant renormalization finished version={} workspaces={} transactions={} keysChanged={}",
                MerchantNormalizer.VERSION, workspaces, total, keys);
        return new Result(workspaces, total, keys);
    }

    private Result renormalize(WorkspaceId workspace) {
        Instant now = clock.instant();
        Set<KeyChange> changes = new LinkedHashSet<>();
        int renormalized = 0;
        TransactionId after = null;

        List<Transaction> batch;
        while (!(batch = transactions.findWithOutdatedMerchant(workspace, MerchantNormalizer.VERSION, after,
                BATCH_SIZE)).isEmpty()) {
            for (Transaction transaction : batch) {
                String oldKey = transaction.merchant().map(Merchant::key).orElseThrow();
                if (!transaction.renormalizeMerchant(now)) {
                    continue;
                }
                transactions.save(transaction);
                events.categorized(transaction);
                renormalized++;
                String newKey = transaction.merchant().map(Merchant::key).orElse(null);
                CategoryNature nature = CategoryNature.acceptedBy(transaction.type()).orElseThrow();
                if (!oldKey.equals(newKey)) {
                    changes.add(new KeyChange(oldKey, newKey, nature));
                }
            }
            after = batch.get(batch.size() - 1).id();
        }

        for (KeyChange change : changes) {
            if (change.to() != null) {
                rules.copy(workspace, change.from(), change.to(), change.nature());
            }
        }
        for (KeyChange change : changes) {
            if (!transactions.merchantKeyInUse(workspace, change.from(), change.nature())) {
                rules.delete(workspace, change.from(), change.nature());
            }
        }

        log.info("Merchants renormalized workspaceId={} transactions={} keysChanged={}", workspace, renormalized,
                changes.size());
        return new Result(1, renormalized, changes.size());
    }

    private record KeyChange(String from, String to, CategoryNature nature) {
    }
}
