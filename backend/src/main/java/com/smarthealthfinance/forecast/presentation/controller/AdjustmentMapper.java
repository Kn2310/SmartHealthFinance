package com.smarthealthfinance.forecast.presentation.controller;

import com.smarthealthfinance.forecast.application.usecase.RecurringSeriesActions;
import com.smarthealthfinance.forecast.presentation.dto.request.ForecastAdjustmentRequest;
import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.math.BigDecimal;

/** Corpo do ajuste para o caso de uso; as regras de valor e dia são validadas no caso de uso, depois da autorização. */
final class AdjustmentMapper {

    private AdjustmentMapper() {
    }

    static RecurringSeriesActions.Edit toEdit(ForecastAdjustmentRequest request) {
        if (request == null) {
            return new RecurringSeriesActions.Edit(null, null);
        }
        if (request.expectedAmount() == null) {
            return new RecurringSeriesActions.Edit(null, request.expectedDay());
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(request.expectedAmount().amount().trim());
        }
        catch (NumberFormatException | NullPointerException e) {
            throw new InvalidValueException("expectedAmount", "INVALID_FORMAT");
        }
        return new RecurringSeriesActions.Edit(amount, request.expectedDay(), request.expectedAmount().currency());
    }
}
