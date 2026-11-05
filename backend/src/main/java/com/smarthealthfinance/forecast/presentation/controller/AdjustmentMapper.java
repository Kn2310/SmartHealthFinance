package com.smarthealthfinance.forecast.presentation.controller;

import com.smarthealthfinance.forecast.application.usecase.RecurringSeriesActions;
import com.smarthealthfinance.forecast.presentation.dto.request.ForecastAdjustmentRequest;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Corpo do ajuste para o caso de uso; as regras de valor e dia são validadas no caso de uso, depois da autorização. */
final class AdjustmentMapper {

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final int MAX_LENGTH = 32;

    private AdjustmentMapper() {
    }

    static RecurringSeriesActions.Edit toEdit(ForecastAdjustmentRequest request) {
        if (request == null) {
            return new RecurringSeriesActions.Edit(null, null);
        }
        if (request.expectedAmount() == null) {
            return new RecurringSeriesActions.Edit(null, request.expectedDay());
        }
        // Só decimal simples, no limite do Money: notação científica ("1e999999999") viraria uma string gigante.
        String raw = request.expectedAmount().amount();
        String text = raw == null ? "" : raw.trim();
        if (text.length() > MAX_LENGTH || !PLAIN_DECIMAL.matcher(text).matches()
                || text.split("\\.", 2)[0].replaceFirst("^-", "").length() > Money.MAX_INTEGER_DIGITS) {
            throw new InvalidValueException("expectedAmount", "INVALID_FORMAT");
        }
        BigDecimal amount = new BigDecimal(text);
        return new RecurringSeriesActions.Edit(amount, request.expectedDay(), request.expectedAmount().currency());
    }
}
