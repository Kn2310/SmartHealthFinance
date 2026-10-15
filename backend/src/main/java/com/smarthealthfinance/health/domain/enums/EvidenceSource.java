package com.smarthealthfinance.health.domain.enums;

/** De onde vem o número de uma evidência (ADR-0017 §6): a mesma porta que alimenta Análise e Contas. */
public enum EvidenceSource {

    ANALYTICS_MONTHLY("analytics.monthly"),
    ANALYTICS_CATEGORIES("analytics.categories"),
    ANALYTICS_INCOME_SOURCES("analytics.income-sources"),
    OVERVIEW_ACCOUNT_BALANCES("overview.account-balances");

    private final String code;

    EvidenceSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
