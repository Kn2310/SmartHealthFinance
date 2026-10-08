package com.smarthealthfinance.ingestion.domain.enums;

/** Situação de uma linha do arquivo (ADR-0009 §7/§11). */
public enum RecordStatus {
    /** Lançamento válido, ainda não gravado (preview ou aguardando processamento). */
    VALID,
    /** Linha recusada; guarda só o código do motivo. */
    INVALID,
    /** Repetida no próprio arquivo ou já importada nesta conta. */
    DUPLICATE,
    /** Virou transação. */
    IMPORTED
}
