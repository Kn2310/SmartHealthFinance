package com.smarthealthfinance.transactions.domain.enums;

import com.smarthealthfinance.shared.domain.InvalidValueException;

/** Tipos da spec 05.4. O valor é sempre positivo: o efeito no saldo vem do tipo (ADR-0005). */
public enum TransactionType {

    INCOME,
    EXPENSE,
    /** Entre duas contas do mesmo Workspace; nunca é despesa nem receita. */
    TRANSFER,
    /** Correção/saldo inicial; a direção é explícita ({@link AdjustmentDirection}). */
    ADJUSTMENT,
    /** Reembolso; pode apontar para a despesa original. */
    REFUND;

    /**
     * Efeito no saldo da conta de origem (ADR-0005/0006). Em TRANSFER, o destino sempre recebe CREDIT.
     *
     * @param direction obrigatória apenas para ADJUSTMENT
     */
    public BalanceEffect originEffect(AdjustmentDirection direction) {
        return switch (this) {
            case INCOME, REFUND -> BalanceEffect.CREDIT;
            case EXPENSE, TRANSFER -> BalanceEffect.DEBIT;
            case ADJUSTMENT -> {
                if (direction == null) {
                    throw new InvalidValueException("adjustmentDirection", "REQUIRED");
                }
                yield direction == AdjustmentDirection.INCREASE ? BalanceEffect.CREDIT : BalanceEffect.DEBIT;
            }
        };
    }

    public static TransactionType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("type", "REQUIRED");
        }

        try {
            return valueOf(value.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidValueException("type", "INVALID");
        }
    }
}
