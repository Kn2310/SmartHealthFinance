package com.smarthealthfinance.transactions.presentation.dto.response;

import com.smarthealthfinance.transactions.application.usecase.AutoCategorizePending;

/**
 * @param evaluated           transações que ainda não tinham sido tratadas e foram avaliadas
 * @param categorized         das avaliadas, as que receberam categoria por regra
 * @param merchantsIdentified das avaliadas, as que passaram a ter comerciante
 */
public record CategorizePendingResponse(int evaluated, int categorized, int merchantsIdentified) {

    public static CategorizePendingResponse from(AutoCategorizePending.Result result) {
        return new CategorizePendingResponse(result.evaluated(), result.categorized(), result.merchantsIdentified());
    }
}
