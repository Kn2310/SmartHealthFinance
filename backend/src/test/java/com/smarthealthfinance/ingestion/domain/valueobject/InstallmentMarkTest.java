package com.smarthealthfinance.ingestion.domain.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

/** Marcador de parcela nas faturas (ADR-0009 §22 e §24); descrições fictícias. */
class InstallmentMarkTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "LOJA TECH NOTEBOOK PARC 03/10 | 3 | 10 | LOJA TECH NOTEBOOK",
            "Loja Tech · Parcela 3 de 10   | 3 | 10 | Loja Tech",
            "LOJA TECH*NOTEBOOK - PARC.3/10 | 3 | 10 | LOJA TECH*NOTEBOOK",
            "PARCELA 01/12 CURSO ONLINE    | 1 | 12 | CURSO ONLINE",
            "LOJA TECH NOTEBOOK 03/10      | 3 | 10 | LOJA TECH NOTEBOOK",
            "Loja Tech - 3/10              | 3 | 10 | Loja Tech",
    })
    void detectsTheMarkerAndRemovesItFromTheDescription(String description, int number, int count, String rest) {
        InstallmentMark.Detected detected = InstallmentMark.detect(description).orElseThrow();

        assertThat(detected.mark()).isEqualTo(new InstallmentMark(number, count));
        assertThat(detected.descriptionWithoutMark()).isEqualTo(rest);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MERCADO BAIRRO",
            "PARC 11/10 LOJA",   // k > n
            "LOJA 1/1",          // n < 2
            "LOJA 3/60",         // n > 48
            "COMPRA 12/07 LOJA", // k/n no meio, sem rótulo
            "UBER 25/09",        // dia/mês: 25 > 9
    })
    void ignoresWhatIsNotAnInstallment(String description) {
        assertThat(InstallmentMark.detect(description)).isEmpty();
    }

    @Test
    void aDateAtTheEndCanLookLikeAnInstallment() {
        // Risco aceito no ADR-0009 §24: o preview mostra "Parcela 5 de 12" para o usuário conferir.
        assertThat(InstallmentMark.detect("CINEMA 05/12")).map(InstallmentMark.Detected::mark)
                .contains(new InstallmentMark(5, 12));
    }

    @ParameterizedTest
    @CsvSource({"3/10, 3, 10", "03/10, 3, 10", "3 de 10, 3, 10", "1 DE 2, 1, 2"})
    void parsesTheColumn(String raw, int number, int count) {
        assertThat(InstallmentMark.parseColumn(raw)).contains(new InstallmentMark(number, count));
    }

    @Test
    void aBlankColumnIsNotAnInstallment() {
        assertThat(InstallmentMark.parseColumn("  ")).isEmpty();
        assertThat(InstallmentMark.parseColumn(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "3/", "a/b", "11/10", "1/1", "0/5", "3/49"})
    void rejectsAnInvalidColumn(String raw) {
        assertInvalidValue(() -> InstallmentMark.parseColumn(raw), "installment", "INVALID_INSTALLMENT");
    }
}
