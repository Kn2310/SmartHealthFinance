package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.application.port.CardLedgerReader.InvoiceItem;

import java.util.List;

public record InvoiceItemPageView(List<InvoiceItem> items, int page, int pageSize, long totalItems) {
}
