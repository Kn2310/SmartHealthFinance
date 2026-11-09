package com.smarthealthfinance.consents.domain.enums;

/**
 * Estados da spec 05.12 (ADR-0038 §3): {@code PENDING} = nunca respondido; {@code EXPIRED} = concedido numa versão
 * anterior do termo (conta como não ativo); {@code FAILED} é reservado para o Open Finance e nunca é produzido aqui.
 */
public enum ConsentState {
    PENDING,
    ACTIVE,
    EXPIRED,
    REVOKED,
    FAILED
}
