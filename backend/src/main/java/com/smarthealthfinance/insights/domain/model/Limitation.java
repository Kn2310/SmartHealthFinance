package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import java.util.Objects;

/** Limitação do insight, com o texto mostrado no detalhe (ADR-0021 §7). */
public record Limitation(LimitationCode code, String text) {

    public Limitation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(text, "text");
    }
}
