package com.smarthealthfinance.ingestion.domain.service;

import com.smarthealthfinance.ingestion.domain.model.StatementLine;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.InstallmentMark;

import java.util.List;
import java.util.Optional;

/**
 * Classificação das linhas de uma fatura de cartão (ADR-0009 §23–§24). Determinística e visível no preview; o sinal
 * já vem normalizado pelo parser (negativo = compra).
 */
public final class CardStatementRules {

    /** Motivo do record {@code SKIPPED}: o pagamento é registrado pela conta (ADR-0025 §7, adendo). */
    public static final String CARD_PAYMENT = "CARD_PAYMENT";

    private static final String OFX_PAYMENT = "PAYMENT";
    private static final List<String> PAYMENT_PREFIXES = List.of("PAGAMENTO", "PAGTO", "PGTO", "PAG FATURA");

    private CardStatementRules() {
    }

    /** Crédito com {@code TRNTYPE} {@code PAYMENT}, ou com descrição que começa como um pagamento. */
    public static boolean isPayment(StatementLine line) {
        if (!line.isInflow()) {
            return false;
        }
        if (OFX_PAYMENT.equalsIgnoreCase(line.bankType())) {
            return true;
        }
        String description = DedupeKey.normalize(line.description());
        return PAYMENT_PREFIXES.stream().anyMatch(prefix -> description.equals(prefix)
                || description.startsWith(prefix + " "));
    }

    /** Parcela de uma compra: a coluna {@code parcela} tem prioridade sobre a descrição. Créditos nunca são parcela. */
    public static Optional<InstallmentMark> installmentOf(StatementLine line) {
        if (line.isInflow()) {
            return Optional.empty();
        }
        if (line.installment() != null) {
            return Optional.of(line.installment());
        }
        return InstallmentMark.detect(line.description()).map(InstallmentMark.Detected::mark);
    }

    /** Descrição da compra: a da linha sem o marcador de parcela. */
    public static String purchaseDescription(StatementLine line) {
        return InstallmentMark.withoutMark(line.description());
    }
}
