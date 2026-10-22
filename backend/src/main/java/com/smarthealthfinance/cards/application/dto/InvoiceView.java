package com.smarthealthfinance.cards.application.dto;

import java.time.LocalDate;

public record InvoiceView(InvoiceSummaryView invoice, long itemCount, LocalDate asOf, String calculationVersion) {
}
