package com.smarthealthfinance.health.domain.enums;

/**
 * Próximos passos determinísticos (ADR-0017 §7), na ordem de prioridade. Cada um aponta para uma tela que já existe;
 * a UI resolve a rota a partir do tipo e dos parâmetros.
 */
public enum NextStepKind {
    /** Fatura vencida com saldo em aberto → fatura do cartão (ADR-0027 §4). */
    PAY_OVERDUE_INVOICE,
    /** Reserva abaixo de 6 meses → Contas. */
    BUILD_RESERVE,
    /** Sem conta de reserva → Adicionar conta. */
    ADD_RESERVE_ACCOUNT,
    /** Categoria acima da média → Análise › Categorias com a categoria. */
    REVIEW_CATEGORY,
    /** Utilização alta → cartão de maior utilização (ADR-0027 §4). */
    REVIEW_CARD_UTILIZATION,
    /** Mais de 10% do gasto sem categoria → Transações sem categoria. */
    CATEGORIZE_TRANSACTIONS,
    /** Mês com resultado negativo → Análise do mês. */
    REVIEW_MONTH
}
