package com.smarthealthfinance.shared.presentation.money;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * Contrato de dinheiro da API (spec 05.6): {@code {"amount": "123.45", "currency": "BRL"}}.
 * O valor trafega como string para nunca passar por ponto flutuante no cliente.
 */
public record MoneyDto(@NotBlank String amount, @NotBlank String currency) {

    /** @param amount já na escala de exibição (ver TransactionView) */
    public static MoneyDto of(BigDecimal amount, String currency) {
        return new MoneyDto(amount.toPlainString(), currency);
    }
}
