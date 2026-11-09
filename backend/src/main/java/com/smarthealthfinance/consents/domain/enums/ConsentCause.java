package com.smarthealthfinance.consents.domain.enums;

/** {@code USER}: a própria pessoa; {@code CASCADE}: revogado porque o pré-requisito foi revogado (ADR-0038 §3). */
public enum ConsentCause {
    USER,
    CASCADE
}
