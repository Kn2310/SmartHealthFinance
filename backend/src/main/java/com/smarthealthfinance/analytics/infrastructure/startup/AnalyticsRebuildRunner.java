package com.smarthealthfinance.analytics.infrastructure.startup;

import com.smarthealthfinance.analytics.application.usecase.RebuildAnalyticsProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Reconstrução da projeção de Analytics na inicialização (ADR-0014 §7, runbook {@code analytics-rebuild.md}).
 * <ul>
 *   <li>{@code shf.analytics.rebuild=missing} (padrão): só Workspaces com transações e sem projeção na versão
 *   atual. Faz o backfill do primeiro deploy e de cada nova versão do algoritmo; idempotente.</li>
 *   <li>{@code all}: todos os Workspaces com transações.</li>
 *   <li>{@code off}: não faz nada.</li>
 * </ul>
 * Uma falha não derruba a API: a projeção também se reconstrói no primeiro evento do Workspace. Roda antes do rebuild
 * do Health, que lê a projeção (ADR-0018 §6).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class AnalyticsRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRebuildRunner.class);

    private final RebuildAnalyticsProjection rebuild;
    private final String mode;

    public AnalyticsRebuildRunner(RebuildAnalyticsProjection rebuild,
                                  @Value("${shf.analytics.rebuild:missing}") String mode) {
        this.rebuild = rebuild;
        this.mode = mode.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        RebuildAnalyticsProjection.Scope scope = switch (mode) {
            case "off" -> null;
            case "all" -> RebuildAnalyticsProjection.Scope.ALL;
            case "missing" -> RebuildAnalyticsProjection.Scope.MISSING;
            default -> throw new IllegalStateException("shf.analytics.rebuild deve ser missing, all ou off");
        };
        if (scope == null) {
            return;
        }
        try {
            rebuild.execute(scope);
        }
        catch (RuntimeException failure) {
            log.error("Analytics projection rebuild failed scope={} error={}", scope,
                    failure.getClass().getSimpleName());
        }
    }
}
