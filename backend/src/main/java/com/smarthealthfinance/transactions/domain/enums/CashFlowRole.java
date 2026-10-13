package com.smarthealthfinance.transactions.domain.enums;

/**
 * Papel de uma transação no fluxo de caixa (ADR-0006 §9, ADR-0014). Fonte única da semântica que o Overview e o
 * Analytics reimplementam em SQL (cada um com seu teste de paridade):
 * <ul>
 *   <li>{@code INCOME}: receita;</li>
 *   <li>{@code EXPENSE}: despesa bruta;</li>
 *   <li>{@code REFUND}: devolução de despesa, nunca receita;</li>
 *   <li>{@code TRANSFER}: só informativo (quantidade e volume), nunca receita nem despesa;</li>
 *   <li>{@code NONE}: ajuste, que move saldo mas não entra no fluxo.</li>
 * </ul>
 */
public enum CashFlowRole {

    INCOME,
    EXPENSE,
    REFUND,
    TRANSFER,
    NONE;

    public static CashFlowRole of(TransactionType type) {
        return switch (type) {
            case INCOME -> INCOME;
            case EXPENSE -> EXPENSE;
            case REFUND -> REFUND;
            case TRANSFER -> TRANSFER;
            case ADJUSTMENT -> NONE;
        };
    }
}
