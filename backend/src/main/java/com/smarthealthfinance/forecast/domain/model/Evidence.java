package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.EvidenceKind;
import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * Evidência rastreável de um valor estimado (ADR-0030 §6). Só ids, meses e valores: nunca nomes, comerciantes ou
 * textos (ADR-0031, Security).
 *
 * @param month mês da amostra ou mês de fechamento da fatura; nulo numa ocorrência
 * @param refId transação (ocorrência) ou cartão (fatura de base); nulo numa amostra mensal
 * @param value valor da amostra; nulo quando só o id importa
 */
public record Evidence(EvidenceKind kind, YearMonth month, UUID refId, Money value) {

    public Evidence {
        Objects.requireNonNull(kind, "kind");
    }

    public static Evidence sample(YearMonth month, Money value) {
        return new Evidence(EvidenceKind.SAMPLE_MONTH, month, null, value);
    }

    public static Evidence occurrence(UUID transactionId) {
        return new Evidence(EvidenceKind.SERIES_OCCURRENCE, null, transactionId, null);
    }

    public static Evidence invoice(UUID cardId, YearMonth closingMonth, Money value) {
        return new Evidence(EvidenceKind.BASE_INVOICE, closingMonth, cardId, value);
    }
}
