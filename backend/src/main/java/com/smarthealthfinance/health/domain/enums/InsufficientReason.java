package com.smarthealthfinance.health.domain.enums;

/** Por que uma dimensão não tem nota (ADR-0017 §2), com o texto exibido no lugar da explicação. */
public enum InsufficientReason {

    NO_CLOSED_MONTH("Precisa de pelo menos 1 mês fechado."),
    NO_CASH_FLOW("Ainda não há receitas nem despesas nos meses fechados."),
    NO_AVERAGE("Precisa de pelo menos 1 mês anterior para comparar."),
    ZERO_AVERAGE("Não houve despesas nos meses anteriores para comparar."),
    NO_RESERVE_ACCOUNT("Nenhuma conta poupança ativa indicada como reserva."),
    ZERO_EXPENSE_AVERAGE("Não houve despesas nos meses fechados para medir a cobertura."),
    NEEDS_THREE_CLOSED_MONTHS("Precisa de 3 meses fechados."),
    REQUIRES_CARDS("Disponível quando houver cartões e parcelamentos.");

    private final String text;

    InsufficientReason(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
