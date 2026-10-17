package com.smarthealthfinance.insights.application.port;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.domain.model.Insight;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Insights guardados e marcação para geração com debounce (ADR-0022 §3/§5). Sempre por Workspace. Exige transação
 * ativa.
 */
public interface InsightStore {

    /**
     * Marca o Workspace: {@code due_at = min(now + quiet, dirty_since + maxWait)}. Cada nova marca adia a geração,
     * nunca além do teto. Bloqueia enquanto o Workspace estiver sendo gerado.
     */
    void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait);

    /**
     * Marca para gerar agora os Workspaces com transações (rebuild, ADR-0022 §7).
     *
     * @param all {@code false}: só os cuja última geração não é da versão; {@code true}: todos com transações
     * @return quantos
     */
    int markForRebuild(String algorithmVersion, boolean all, Instant now);

    /** Pega e bloqueia um Workspace vencido que nenhuma outra instância esteja gerando ({@code SKIP LOCKED}). */
    Optional<WorkspaceId> claimDue(Instant now);

    /** Todos os insights do Workspace, de qualquer versão e estado (entrada da reconciliação e da relevância). */
    List<Insight> findAll(WorkspaceId workspaceId);

    /** Insere ou atualiza por (Workspace, fingerprint, versão). */
    void save(List<Insight> insights);

    /** Registra a geração (versão, momento e data de negócio) e limpa a marca. */
    void finishGeneration(WorkspaceId workspaceId, String algorithmVersion, Instant generatedAt, LocalDate asOf);

    /** Adia um Workspace cuja geração falhou, sem limpar a marca. */
    void postpone(WorkspaceId workspaceId, Instant dueAt);

    /** {@code dirty_since} mais antigo entre os Workspaces marcados (métrica de freshness). */
    Optional<Instant> oldestDirtySince();

    /** Apaga insights não visíveis com {@code updated_at} anterior a {@code before} (retenção). @return quantos */
    int purgeHiddenBefore(Instant before);

    GenerationState state(WorkspaceId workspaceId, String algorithmVersion);

    /**
     * @param dirty           marcado para geração
     * @param built           a última geração é da versão atual
     * @param generatedAt     última geração; nula se nunca
     * @param asOf            data de negócio da última geração; nula se nunca
     * @param hasTransactions o Workspace tem transações
     * @param pendingEvents   {@code health.snapshot-updated} ainda não marcados pelo consumidor (inclui DLQ)
     */
    record GenerationState(boolean dirty, boolean built, Instant generatedAt, LocalDate asOf,
                           boolean hasTransactions, int pendingEvents) {}
}
