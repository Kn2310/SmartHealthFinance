package com.smarthealthfinance.insights.application.exception;

/**
 * A transação de {@code .../insights/related?transactionId=} não existe no Workspace: responde
 * {@code 404 TRANSACTION_NOT_FOUND}, como a API de Transações (ADR-0005), sem depender dos casos de uso dela.
 */
public class RelatedTransactionNotFoundException extends RuntimeException {

    public RelatedTransactionNotFoundException() {
        super("Transação não encontrada");
    }
}
