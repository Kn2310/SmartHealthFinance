package com.smarthealthfinance.insights.infrastructure.startup;

import com.smarthealthfinance.insights.application.usecase.RebuildInsights;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rebuild dos insights na inicialização (ADR-0022 §7, runbook {@code insights-rebuild.md}). Só marca os Workspaces
 * para o job; a ordem em relação aos runners do Analytics e do Health não importa.
 * <ul>
 *   <li>{@code shf.insights.rebuild=missing} (padrão): Workspaces com transações cuja última geração não é da versão
 *   atual (backfill do deploy e de cada nova versão); idempotente;</li>
 *   <li>{@code all}: todos os Workspaces com transações;</li>
 *   <li>{@code off}: não faz nada.</li>
 * </ul>
 * Uma falha não derruba a API: o Workspace é marcado de novo no próximo {@code health.snapshot-updated}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class InsightsRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InsightsRebuildRunner.class);

    private final RebuildInsights rebuild;
    private final String mode;

    public InsightsRebuildRunner(RebuildInsights rebuild, @Value("${shf.insights.rebuild:missing}") String mode) {
        this.rebuild = rebuild;
        this.mode = mode.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        RebuildInsights.Scope scope = switch (mode) {
            case "off" -> null;
            case "all" -> RebuildInsights.Scope.ALL;
            case "missing" -> RebuildInsights.Scope.MISSING;
            default -> throw new IllegalStateException("shf.insights.rebuild deve ser missing, all ou off");
        };
        if (scope == null) {
            return;
        }
        try {
            rebuild.execute(scope);
        }
        catch (RuntimeException failure) {
            log.error("Insights rebuild failed scope={} error={}", scope, failure.getClass().getSimpleName());
        }
    }
}
