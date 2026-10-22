package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.Money;

/**
 * Utilização do limite (ADR-0025 §9). Sem crédito, previousOutstanding + currentInvoice + futureInstallments é
 * exatamente utilized.
 *
 * @param available                       limite − utilizado; negativo quando o limite foi excedido
 * @param credit                          saldo a favor depois de abater todas as faturas, inclusive as futuras
 * @param utilizationPercent              utilizado ÷ limite, inteiro HALF_EVEN
 * @param utilizationWithoutFuturePercent (faturas anteriores em aberto + fatura atual) ÷ limite
 */
public record CardUtilization(
        Money limit,
        Money utilized,
        Money available,
        Money credit,
        int utilizationPercent,
        int utilizationWithoutFuturePercent,
        Money previousOutstanding,
        Money currentInvoice,
        Money futureInstallments
) {
}
