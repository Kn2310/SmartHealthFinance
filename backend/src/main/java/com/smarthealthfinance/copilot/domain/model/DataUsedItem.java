package com.smarthealthfinance.copilot.domain.model;

import java.util.Objects;

/** Uma fonte de dados da resposta, com rótulo legível pt-BR (ex.: "Fatura out/2026") e se o período é parcial. */
public record DataUsedItem(String label, boolean partial) {

    public DataUsedItem {
        Objects.requireNonNull(label, "label");
    }
}
