package com.smarthealthfinance.forecast.application.port;

import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Séries recorrentes do Workspace (ADR-0031 §4). Sempre por Workspace; exige transação ativa. Nenhum método apaga
 * séries, e a redetecção nunca escreve o estado do usuário.
 */
public interface RecurringSeriesRepository {

    /** O id opaco da API (ADR-0032 §2) e a chave natural de uma série. */
    record SeriesRef(UUID id, SeriesDirection direction, String key) {}

    /** Todas as séries do Workspace, ordenadas por sentido e chave. */
    List<RecurringSeries> findAll(WorkspaceId workspaceId);

    /** Id e chave de todas as séries do Workspace. */
    List<SeriesRef> findAllRefs(WorkspaceId workspaceId);

    /** A série pelo id da API; vazio se não existe neste Workspace (outro Workspace = inexistente). */
    Optional<SeriesRef> findRef(WorkspaceId workspaceId, UUID seriesId);

    Optional<RecurringSeries> find(WorkspaceId workspaceId, SeriesDirection direction, String key);

    /** Igual a {@link #find}, bloqueando a linha até o fim da transação (ações do usuário). */
    Optional<RecurringSeries> findForUpdate(WorkspaceId workspaceId, SeriesDirection direction, String key);

    /**
     * Grava o resultado da redetecção: séries novas entram inteiras (com id novo); nas existentes só mudam as colunas
     * <b>detectadas</b> (rótulo, canal, dia, valor, faixa, confiança, ocorrências, versão). {@code id}, {@code status},
     * {@code origin}, {@code user_*} e {@code status_changed_at} nunca são escritos aqui.
     */
    void upsertDetected(WorkspaceId workspaceId, Collection<RecurringSeries> series, Instant now);

    /** Grava só o estado do usuário ({@code status}, {@code user_expected_*}, {@code status_changed_at}). */
    void saveUserState(WorkspaceId workspaceId, RecurringSeries series, Instant now);
}
