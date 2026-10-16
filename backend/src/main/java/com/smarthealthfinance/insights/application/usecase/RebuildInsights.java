package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.domain.service.InsightRules;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuild dos insights (ADR-0022 §7, runbook {@code insights-rebuild.md}): marca os Workspaces para o job gerar agora,
 * em vez de gerar aqui, e a geração reconcilia (nunca apaga dispensa, visto ou feedback). Não depende da ordem dos
 * runners: se o Health ainda estiver reconstruindo, o {@code health.snapshot-updated} dele gera de novo.
 */
@Service
public class RebuildInsights {

    private static final Logger log = LoggerFactory.getLogger(RebuildInsights.class);

    private final InsightStore store;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RebuildInsights(InsightStore store, TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
    }

    public enum Scope {
        /** Workspaces com transações cuja última geração não é da versão atual (deploy e nova versão). */
        MISSING,
        /** Todos os Workspaces com transações (runbook). */
        ALL
    }

    /** @return quantos Workspaces foram marcados */
    public int execute(Scope scope) {
        Integer marked = transactions.execute(status -> store.markForRebuild(InsightRules.ALGORITHM_VERSION,
                scope == Scope.ALL, clock.instant()));
        int count = marked == null ? 0 : marked;
        log.info("Insights rebuild marked scope={} workspaces={}", scope, count);
        return count;
    }
}
