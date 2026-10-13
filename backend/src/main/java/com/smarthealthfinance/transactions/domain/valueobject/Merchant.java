package com.smarthealthfinance.transactions.domain.valueobject;

import java.util.Objects;

/**
 * Comerciante identificado a partir da descrição da transação (ADR-0013 §1).
 *
 * @param key               forma normalizada (minúsculas, sem acento) usada para agrupar e para as regras
 * @param name              nome de exibição
 * @param normalizerVersion versão do algoritmo que produziu a chave
 */
public record Merchant(String key, String name, String normalizerVersion) {

    public static final int MAX_LENGTH = 120;

    public Merchant {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(normalizerVersion, "normalizerVersion");
        if (key.isBlank() || key.length() > MAX_LENGTH || name.isBlank() || name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Comerciante fora do formato");
        }
    }
}
