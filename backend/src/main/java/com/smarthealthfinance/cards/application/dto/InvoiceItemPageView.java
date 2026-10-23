package com.smarthealthfinance.cards.application.dto;

import java.util.List;

public record InvoiceItemPageView(List<InvoiceItemView> items, int page, int pageSize, long totalItems) {
}
