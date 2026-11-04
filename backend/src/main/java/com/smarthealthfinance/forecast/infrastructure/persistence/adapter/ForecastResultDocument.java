package com.smarthealthfinance.forecast.infrastructure.persistence.adapter;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

/**
 * Forma do {@code result} JSONB de {@code forecast_snapshots} (ADR-0031 §4): o {@link ForecastResult} inteiro, com as
 * projeções, as partes, as faixas, a confiança, as limitações, as evidências e a situação das séries no cálculo.
 * <p>
 * O mapper é próprio (não o do Spring), para a forma gravada não mudar com a configuração do HTTP: datas em ISO,
 * enums pelo nome e todo {@link BigDecimal} (dinheiro, percentuais) como <b>string</b>, nunca número JSON. A ida e a
 * volta são exatas ({@code Money} reaplica a escala 4). O domínio continua sem anotações.
 */
final class ForecastResultDocument {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .withConfigOverride(BigDecimal.class,
                    override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    private ForecastResultDocument() {
    }

    static String toJson(ForecastResult result) {
        return MAPPER.writeValueAsString(result);
    }

    static ForecastResult fromJson(String json) {
        return MAPPER.readValue(json, ForecastResult.class);
    }
}
