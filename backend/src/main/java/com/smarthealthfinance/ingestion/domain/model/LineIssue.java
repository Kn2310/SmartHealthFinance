package com.smarthealthfinance.ingestion.domain.model;

import java.util.Objects;

/**
 * Linha do extrato que não pôde virar lançamento (ADR-0009 §7). Guarda só a posição, o campo e um código
 * estável: nunca o valor bruto, que é dado financeiro.
 *
 * @param lineNumber posição no arquivo (CSV: linha física, com o cabeçalho na linha 1; OFX: ordem do STMTTRN)
 */
public record LineIssue(int lineNumber, String field, String code) {

    public LineIssue {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
    }
}
