package com.smarthealthfinance.ingestion.domain.enums;

/** De onde é o extrato: o destino escolhido no upload decide (ADR-0009 §4 e §19). */
public enum StatementKind {
    /** Extrato de conta: {@code STMTRS} no OFX; no CSV, negativo = saída. */
    ACCOUNT,
    /** Fatura de cartão: {@code CCSTMTRS} no OFX; no CSV, positivo = compra (ADR-0009 §21–§22). */
    CARD
}
