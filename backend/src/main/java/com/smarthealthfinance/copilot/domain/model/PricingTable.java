package com.smarthealthfinance.copilot.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Tabela de preços versionada na configuração (ADR-0039 §1, §10): o ledger grava a versão usada em cada custo. */
public record PricingTable(String version, Map<String, ModelPrice> byModel) {

    public PricingTable {
        Objects.requireNonNull(version, "version");
        if (version.isBlank()) {
            throw new IllegalArgumentException("pricing version is required");
        }
        byModel = Map.copyOf(byModel);
    }

    public static PricingTable of(String version, List<ModelPrice> prices) {
        return new PricingTable(version,
                prices.stream().collect(Collectors.toMap(ModelPrice::model, Function.identity())));
    }

    public Optional<ModelPrice> find(String model) {
        return Optional.ofNullable(byModel.get(model));
    }
}
