package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * Evolução da utilização de um cartão (ADR-0029 §2): o mesmo razão da utilização calculado no fim de cada mês e hoje,
 * só com os fatos até cada data e com o limite atual (adendo do ADR-0025 §9).
 *
 * @param points em ordem de data; o último é hoje
 */
public record UtilizationHistoryView(List<Point> points, LocalDate asOf, String calculationVersion) {

    /** @param current {@code true} só no ponto de hoje (os demais são o último dia de um mês) */
    public record Point(LocalDate date, Money utilized, Money limit, int utilizationPercent, boolean current) {}
}
