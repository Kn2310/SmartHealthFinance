package com.smarthealthfinance.insights.presentation.dto.request;

/** @param value {@code USEFUL}, {@code NOT_USEFUL} ou {@code null} (remove o feedback) — ADR-0021 §8 */
public record InsightFeedbackRequest(String value) {
}
