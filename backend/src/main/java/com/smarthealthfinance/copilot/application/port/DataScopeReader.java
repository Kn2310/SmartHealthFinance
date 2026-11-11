package com.smarthealthfinance.copilot.application.port;

import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * Meses com dados do Workspace que o Copilot pode usar (ADR-0040 §8), por um caso de uso de leitura da lista fechada.
 * Vazio quando não há dado ou não há como saber.
 */
public interface DataScopeReader {

    Optional<Scope> read(UUID workspaceId);

    /** Primeiro e último mês com movimentação. */
    record Scope(YearMonth from, YearMonth to) {
    }
}
