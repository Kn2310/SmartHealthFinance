package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.Money;

import java.util.Objects;

/**
 * Entrada do razão para uma fatura: o que foi faturado nela.
 *
 * @param charges parcelas de compras {@code POSTED}, já sem a parte cancelada por estorno
 * @param credits créditos (estornos) atribuídos à fatura
 */
public record InvoiceBalance(
        InvoiceDates dates,
        Money charges,
        Money credits
) {
    public InvoiceBalance {
        Objects.requireNonNull(dates, "dates");
        Objects.requireNonNull(charges, "charges");
        Objects.requireNonNull(credits, "credits");
    }

    /** Pode ser negativo quando os créditos superam as compras. */
    public Money amount() {
        return charges.minus(credits);
    }
}
