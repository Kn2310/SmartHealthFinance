package com.smarthealthfinance.ingestion.domain.service;

import com.smarthealthfinance.ingestion.domain.model.StatementLine;
import com.smarthealthfinance.ingestion.domain.valueobject.InstallmentMark;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

/** Classificação das linhas de fatura (ADR-0009 §23–§24). Sinal já normalizado: negativo = compra. */
class CardStatementRulesTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @ParameterizedTest
    @ValueSource(strings = {"PAGAMENTO RECEBIDO", "Pagamento efetuado", "PAGTO DEBITO AUTOMATICO", "PGTO FATURA",
            "PAG FATURA CARTAO", "Pagamento"})
    void creditsThatLookLikePaymentsAreSkipped(String description) {
        assertThat(CardStatementRules.isPayment(line("3412.80", description, null))).isTrue();
    }

    @Test
    void anOfxPaymentIsSkippedWhateverTheDescription() {
        assertThat(CardStatementRules.isPayment(line("3412.80", "OBRIGADO", "PAYMENT"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ESTORNO LOJA TECH", "PAGAMENTOS ONLINE LTDA", "CREDITO PROMOCIONAL"})
    void otherCreditsAreRefunds(String description) {
        assertThat(CardStatementRules.isPayment(line("100.00", description, null))).isFalse();
    }

    @Test
    void aPurchaseIsNeverAPayment() {
        assertThat(CardStatementRules.isPayment(line("-100.00", "PAGAMENTO DE BOLETO", "PAYMENT"))).isFalse();
    }

    @Test
    void theColumnWinsOverTheDescription() {
        StatementLine line = line("-480.00", "LOJA TECH PARC 02/10", null).withCardData(new InstallmentMark(3, 10),
                null);

        assertThat(CardStatementRules.installmentOf(line)).contains(new InstallmentMark(3, 10));
        assertThat(CardStatementRules.purchaseDescription(line)).isEqualTo("LOJA TECH");
    }

    @Test
    void creditsAreNeverInstallments() {
        assertThat(CardStatementRules.installmentOf(line("480.00", "ESTORNO PARC 03/10", null))).isEmpty();
    }

    private static StatementLine line(String signedAmount, String description, String bankType) {
        return StatementLine.of(2, LocalDate.of(2026, 9, 12), Money.of(signedAmount, BRL), description, null)
                .withCardData(null, bankType);
    }
}
