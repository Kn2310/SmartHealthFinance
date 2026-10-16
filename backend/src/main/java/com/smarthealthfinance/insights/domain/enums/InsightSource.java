package com.smarthealthfinance.insights.domain.enums;

/** Porta que produziu um número do insight (ADR-0021 §7): a mesma que alimenta a Análise e o Health. */
public enum InsightSource {

    ANALYTICS_CATEGORIES("analytics.categories"),
    ANALYTICS_SPENDING("analytics.spending"),
    ANALYTICS_INCOME_SOURCES("analytics.income-sources"),
    ANALYTICS_MERCHANTS("analytics.merchants"),
    TRANSACTIONS_LARGEST_EXPENSES("transactions.largest-expenses"),
    HEALTH_SNAPSHOT("health.snapshot"),
    OVERVIEW_ACCOUNT_BALANCES("overview.account-balances");

    private final String code;

    InsightSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
