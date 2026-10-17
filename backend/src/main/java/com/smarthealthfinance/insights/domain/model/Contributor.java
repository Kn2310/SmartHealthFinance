package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Um dos maiores contribuintes (ADR-0021 §7).
 *
 * @param key    chave do comerciante, código da categoria ou id da transação; nula = não identificado
 * @param name   nome de exibição; nunca a descrição original da transação
 * @param amount valor (ou diferença, em D2) no período
 * @param count  compras; nulo quando não se aplica
 * @param share  % do total do insight, 1 casa; nulo quando não se aplica
 * @param day    data (transação ou cobrança); nula quando não se aplica
 */
public record Contributor(ContributorKind kind, String key, String name, BigDecimal amount, Integer count,
                          BigDecimal share, LocalDate day) {

    public Contributor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(amount, "amount");
    }
}
