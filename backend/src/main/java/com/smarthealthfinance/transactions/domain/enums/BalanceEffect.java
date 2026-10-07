package com.smarthealthfinance.transactions.domain.enums;

/** Sentido do efeito de uma transação no saldo de uma conta. O valor da transação é sempre positivo (ADR-0005). */
public enum BalanceEffect {
    CREDIT,
    DEBIT
}
