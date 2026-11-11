package com.smarthealthfinance.copilot.application.exception;

/**
 * O alvo de uma tool (cartão, fatura, objetivo, insight) não existe mais no Workspace: apagado entre o catálogo e a
 * tool, ou nunca existiu. O pipeline responde {@code INSUFFICIENT_DATA}, sem chamar o modelo de resposta
 * (ADR-0040 §9). Traduzida pelas tools a partir das exceções de "não encontrado" dos casos de uso.
 */
public final class TargetNotFoundException extends RuntimeException {

    public TargetNotFoundException() {
        super("Copilot target not found");
    }
}
