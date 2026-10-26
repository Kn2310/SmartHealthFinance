package com.smarthealthfinance.cards.presentation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * "Marcar como pagamento de fatura" (ADR-0025 §7, adendo).
 *
 * @param transactionId débito importado na conta ({@code EXPENSE} {@code POSTED}, origem {@code IMPORT})
 */
public record CardPaymentReconciliationRequest(@NotNull UUID transactionId) {
}
