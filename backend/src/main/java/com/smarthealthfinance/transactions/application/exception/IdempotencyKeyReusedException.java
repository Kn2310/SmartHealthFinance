package com.smarthealthfinance.transactions.application.exception;

/** A mesma Idempotency-Key foi enviada com outro conteúdo (request hash diferente, spec 05.6). */
public final class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("Idempotency-Key reused with a different request");
    }
}
