package com.smarthealthfinance.health.infrastructure.startup;

import com.smarthealthfinance.health.application.usecase.RebuildHealthSnapshots;
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
 * Reconstrução do snapshot do Health na inicialização (ADR-0018 §6, runbook {@code health-rebuild.md}), depois do
 * rebuild do Analytics (o Health lê a projeção):
 * <ul>
 *   <li>{@code shf.health.rebuild=missing} (padrão): Workspaces com transações e sem snapshot na versão atual do
 *   algoritmo (backfill do deploy e de cada nova versão); idempotente;</li>
 *   <li>{@code all}: todos os Workspaces com transações;</li>
 *   <li>{@code off}: não faz nada.</li>
 * </ul>
 * Uma falha não derruba a API: o Workspace é marcado de novo no próximo evento ou no job diário.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class HealthRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HealthRebuildRunner.class);

    private final RebuildHealthSnapshots rebuild;
    private final String mode;

    public HealthRebuildRunner(RebuildHealthSnapshots rebuild, @Value("${shf.health.rebuild:missing}") String mode) {
        this.rebuild = rebuild;
        this.mode = mode.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        RebuildHealthSnapshots.Scope scope = switch (mode) {
            case "off" -> null;
            case "all" -> RebuildHealthSnapshots.Scope.ALL;
            case "missing" -> RebuildHealthSnapshots.Scope.MISSING;
            default -> throw new IllegalStateException("shf.health.rebuild deve ser missing, all ou off");
        };
        if (scope == null) {
            return;
        }
        try {
            rebuild.execute(scope);
        }
        catch (RuntimeException failure) {
            log.error("Health snapshot rebuild failed scope={} error={}", scope, failure.getClass().getSimpleName());
        }
    }
}
