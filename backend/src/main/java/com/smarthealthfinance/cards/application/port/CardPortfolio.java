package com.smarthealthfinance.cards.application.port;

import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Porta só de leitura dos cartões para Overview, Health e Insights (ADR-0027 §1). Não autoriza: quem chama já leu o
 * Workspace autorizado ou roda num job do próprio Workspace.
 */
public interface CardPortfolio {

    /**
     * Posição dos cartões <b>ativos hoje</b> em cada data pedida, considerando só os fatos até ela. Um cartão só entra
     * numa data se o acompanhamento dele já tinha começado (ADR-0025 §15). Uma leitura de faturas e uma de fatos por
     * chamada.
     *
     * @return para cada data, as posições na ordem de criação dos cartões
     */
    Map<LocalDate, List<CardPosition>> positions(WorkspaceId workspaceId, Collection<LocalDate> dates);
}
