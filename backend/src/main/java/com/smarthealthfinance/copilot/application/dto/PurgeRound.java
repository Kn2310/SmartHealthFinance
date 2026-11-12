package com.smarthealthfinance.copilot.application.dto;

/** O que uma rodada da retenção apagou (ADR-0040 §6): contagens, nunca ids nem conteúdo. */
public record PurgeRound(int messages, int conversations, int feedback) {

    /** Verdadeiro se alguma das etapas encheu o lote: pode haver mais a apagar. */
    public boolean full(int batchSize) {
        return messages >= batchSize || conversations >= batchSize || feedback >= batchSize;
    }
}
