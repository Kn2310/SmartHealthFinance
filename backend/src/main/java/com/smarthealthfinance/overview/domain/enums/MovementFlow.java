package com.smarthealthfinance.overview.domain.enums;

import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.BalanceEffect;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;

/** Sentido de uma movimentação para a UI (sinal + texto + ícone). Transferência nunca é entrada nem saída. */
public enum MovementFlow {

    INFLOW,
    OUTFLOW,
    TRANSFER;

    /** Deriva do mesmo contrato do saldo ({@link TransactionType#originEffect}): não há segunda regra. */
    public static MovementFlow of(TransactionType type, AdjustmentDirection direction) {
        if (type == TransactionType.TRANSFER) {
            return TRANSFER;
        }
        return type.originEffect(direction) == BalanceEffect.CREDIT ? INFLOW : OUTFLOW;
    }
}
