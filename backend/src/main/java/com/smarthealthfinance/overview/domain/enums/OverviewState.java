package com.smarthealthfinance.overview.domain.enums;

/**
 * O que o Overview tem para mostrar. Permite à UI distinguir "sem dados" de "dados reais com valor zero" (ADR-0006):
 * só NO_ACCOUNTS e NO_TRANSACTIONS omitem os valores monetários; nos demais, zero é um fato.
 */
public enum OverviewState {

    /** Nenhuma conta ativa no Workspace. */
    NO_ACCOUNTS,
    /** Há contas, mas nenhuma transação lançada (POSTED) ainda: o saldo seria só ausência de dados. */
    NO_TRANSACTIONS,
    /** Há histórico, mas nada lançado dentro do período. Os zeros do período são reais. */
    NO_ACTIVITY_IN_PERIOD,
    READY;

    public static OverviewState resolve(int activeAccounts, boolean hasPostedTransactions, int postedInPeriod) {
        if (activeAccounts == 0) {
            return NO_ACCOUNTS;
        }
        if (!hasPostedTransactions) {
            return NO_TRANSACTIONS;
        }
        return postedInPeriod == 0 ? NO_ACTIVITY_IN_PERIOD : READY;
    }

    /** Há base para exibir saldo e fluxo de caixa. */
    public boolean hasFinancialData() {
        return this == READY || this == NO_ACTIVITY_IN_PERIOD;
    }
}
