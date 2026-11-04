package com.smarthealthfinance.forecast.domain.enums;

/** Natureza de um valor do Forecast (ADR-0030 §1). */
public enum Basis {

    /** Transações lançadas, saldos, parcelas contratadas e datas do calendário do cartão. */
    FACT,
    /** Depende do padrão histórico. Um total que junta fato e estimativa é sempre {@code ESTIMATED}. */
    ESTIMATED,
    /** Reservado para a Sessão 7.2: o {@code forecast-v1} nunca produz este valor. */
    SIMULATED
}
