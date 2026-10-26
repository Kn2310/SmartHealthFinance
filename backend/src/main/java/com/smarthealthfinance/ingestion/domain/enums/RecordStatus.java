package com.smarthealthfinance.ingestion.domain.enums;

/** Situação de uma linha do arquivo (ADR-0009 §7/§11, §23/§26). */
public enum RecordStatus {
    /** Lançamento válido, ainda não gravado (preview ou aguardando processamento). */
    VALID,
    /** Linha recusada; guarda só o código do motivo. */
    INVALID,
    /** Repetida no próprio arquivo ou já importada nesta conta ou neste cartão. */
    DUPLICATE,
    /** Lida, mas não vira transação: pagamento no arquivo do cartão, que é registrado pela conta. */
    SKIPPED,
    /** Virou transação. */
    IMPORTED,
    /** Parcela de uma compra já lançada no cartão: nada novo foi lançado. */
    RECONCILED
}
