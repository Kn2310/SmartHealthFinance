package com.smarthealthfinance.copilot.domain.enums;

/** Motivo opcional de uma avaliação negativa (ADR-0040 §8). Sem texto livre: o motivo é sempre um código. */
public enum FeedbackReason {
    WRONG_NUMBERS,
    DID_NOT_ANSWER,
    CONFUSING,
    OTHER
}
