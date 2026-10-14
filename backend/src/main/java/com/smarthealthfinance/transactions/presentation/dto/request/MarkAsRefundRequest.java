package com.smarthealthfinance.transactions.presentation.dto.request;

import java.util.UUID;

/**
 * @param refundOfTransactionId despesa original (EXPENSE lançada do Workspace); omitido = reembolso sem vínculo
 */
public record MarkAsRefundRequest(UUID refundOfTransactionId) {
}
