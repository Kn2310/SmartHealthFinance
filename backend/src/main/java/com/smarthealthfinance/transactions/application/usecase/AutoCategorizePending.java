package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.service.AutoCategorizer;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.service.MerchantNormalizer;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Categorização retroativa sob demanda (ADR-0013 §4): identifica o comerciante e aplica as regras às transações que
 * ainda ninguém tratou. Nunca toca em categoria escolhida pelo usuário, inclusive "Sem categoria". Idempotente:
 * rodar de novo só avalia o que ainda está sem tratamento.
 */
@Service
public class AutoCategorizePending {

    private static final Logger log = LoggerFactory.getLogger(AutoCategorizePending.class);

    private static final int BATCH_SIZE = 500;

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final AutoCategorizer categorizer;
    private final Clock clock;

    public AutoCategorizePending(TransactionAccess access, TransactionRepository transactions,
                                 AutoCategorizer categorizer, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.categorizer = categorizer;
        this.clock = clock;
    }

    /**
     * @param evaluated           transações avaliadas
     * @param categorized         transações que receberam categoria
     * @param merchantsIdentified transações que passaram a ter comerciante
     */
    public record Result(int evaluated, int categorized, int merchantsIdentified) {
    }

    @Transactional
    public Result execute(UUID workspaceId) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        AutoCategorizer.Session session = categorizer.forWorkspace(workspace);
        Instant now = clock.instant();

        int evaluated = 0;
        int categorized = 0;
        int merchants = 0;
        TransactionId after = null;

        List<Transaction> batch;
        while (!(batch = transactions.findAwaitingAutomation(workspace, after, BATCH_SIZE)).isEmpty()) {
            for (Transaction transaction : batch) {
                evaluated++;
                boolean identified = transaction.identifyMerchant(
                        MerchantNormalizer.normalize(transaction.description().value()).orElse(null), now);
                boolean categorizedNow = session.apply(transaction, now);
                if (identified || categorizedNow) {
                    transactions.save(transaction);
                }
                merchants += identified ? 1 : 0;
                categorized += categorizedNow ? 1 : 0;
            }
            after = batch.get(batch.size() - 1).id();
        }

        log.info("Pending transactions categorized workspaceId={} evaluated={} categorized={} merchants={}",
                workspace, evaluated, categorized, merchants);
        return new Result(evaluated, categorized, merchants);
    }
}
