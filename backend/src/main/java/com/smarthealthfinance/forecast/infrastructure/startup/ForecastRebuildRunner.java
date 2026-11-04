package com.smarthealthfinance.forecast.infrastructure.startup;

import com.smarthealthfinance.forecast.application.usecase.RebuildForecastSnapshots;
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
 * Reconstrução do snapshot do Forecast na inicialização (ADR-0031 §8, runbook {@code forecast-rebuild.md}), depois do
 * rebuild do Analytics (o Forecast lê a projeção):
 * <ul>
 *   <li>{@code shf.forecast.rebuild=missing} (padrão): Workspaces com transações e sem snapshot na versão atual do
 *   algoritmo (backfill do deploy e de cada nova versão); idempotente;</li>
 *   <li>{@code all}: todos os Workspaces com transações;</li>
 *   <li>{@code off}: não faz nada.</li>
 * </ul>
 * Uma falha não derruba a API: o Workspace é marcado de novo no próximo evento ou no job diário.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ForecastRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ForecastRebuildRunner.class);

    private final RebuildForecastSnapshots rebuild;
    private final String mode;

    public ForecastRebuildRunner(RebuildForecastSnapshots rebuild, @Value("${shf.forecast.rebuild:missing}") String mode) {
        this.rebuild = rebuild;
        this.mode = mode.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        RebuildForecastSnapshots.Scope scope = switch (mode) {
            case "off" -> null;
            case "all" -> RebuildForecastSnapshots.Scope.ALL;
            case "missing" -> RebuildForecastSnapshots.Scope.MISSING;
            default -> throw new IllegalStateException("shf.forecast.rebuild deve ser missing, all ou off");
        };
        if (scope == null) {
            return;
        }
        try {
            rebuild.execute(scope);
        }
        catch (RuntimeException failure) {
            log.error("Forecast snapshot rebuild failed scope={} error={}", scope, failure.getClass().getSimpleName());
        }
    }
}
