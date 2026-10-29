package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Detecção de recorrências do ADR-0030 §2 (tabela por regra). Hoje = 22/09/2026: a janela é mar–ago/2026. */
class RecurrenceDetectorTest {

    private static final java.time.LocalDate TODAY = date("2026-09-22");

    private static List<RecurringSeries> detect(ForecastFixture fixture) {
        return RecurrenceDetector.detect(fixture.entries(), TODAY);
    }

    @Test
    void windowIsTheSixClosedMonthsBeforeTheCurrentOne() {
        assertThat(RecurrenceDetector.windowMonths(TODAY)).extracting(Object::toString)
                .containsExactly("2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08");
    }

    /** Valores por mês (mar–ago, vazio = sem cobrança) → candidata? e confiança. */
    @ParameterizedTest(name = "{0} → {1} / {2}")
    @CsvSource(delimiter = '|', value = {
            // exatamente 3 meses, idênticos: MEDIUM (amplitude 0 ≤ 10%)
            "100,100,100,,,|true|MEDIUM",
            // 5 meses idênticos: HIGH
            "100,100,100,100,100,|true|HIGH",
            // 6 meses idênticos: HIGH
            "100,100,100,100,100,100|true|HIGH",
            // 5 meses com amplitude de 5% exatos (95–100,mediana 100): HIGH
            "95,100,100,100,100,|true|HIGH",
            // 5 meses com amplitude de 6%: MEDIUM (≥ 4 meses)
            "94,100,100,100,100,|true|MEDIUM",
            // 4 meses com amplitude de 20% (90–110, dentro de ±10%): MEDIUM (≥ 4 meses)
            "90,100,100,110,,|true|MEDIUM",
            // 3 meses com amplitude de 10% (95–105): MEDIUM
            "95,100,105,,,|true|MEDIUM",
            // 3 meses com amplitude de 15% (92,5–107,5): LOW
            "92.50,100,107.50,,,|true|LOW",
            // 3 meses, uma ocorrência 12% acima da mediana: fora da tolerância de ±10%
            "100,100,112,,,|false|-",
            // só 2 meses
            "100,100,,,,|false|-",
            // piso de R$ 10: 9,90 não entra, 10,00 entra
            "9.90,9.90,9.90,,,|false|-",
            "10.00,10.00,10.00,,,|true|MEDIUM",
    })
    void candidatesAndConfidence(String amounts, boolean candidate, String confidence) {
        ForecastFixture fixture = new ForecastFixture();
        String[] values = amounts.split(",", -1);
        for (int i = 0; i < values.length; i++) {
            if (!values[i].isBlank()) {
                fixture.add("2026-%02d-10".formatted(3 + i), CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo",
                        values[i]);
            }
        }
        List<RecurringSeries> detected = detect(fixture);
        assertThat(detected).hasSize(candidate ? 1 : 0);
        if (candidate) {
            assertThat(detected.getFirst().confidence()).isEqualTo(Confidence.valueOf(confidence));
        }
    }

    @Test
    void detectedSeriesCarriesTheDetectedFieldsOnly() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "imobiliaria", 5, "HOUSING", "2026-04=1980.00", "2026-05=1980.00",
                        "2026-06=2000.00", "2026-07=1990.00");
        RecurringSeries series = detect(fixture).getFirst();

        assertThat(series.direction()).isEqualTo(SeriesDirection.EXPENSE);
        assertThat(series.key()).isEqualTo("merchant:imobiliaria");
        assertThat(series.label()).isEqualTo("IMOBILIARIA");
        assertThat(series.expectedDay()).isEqualTo(5);
        // mediana de quantidade par: média dos dois do meio (1980 e 1990 → 1985,00)
        assertThat(series.expectedAmount()).isEqualTo(brl("1985.00"));
        assertThat(series.amountRange().min()).isEqualTo(brl("1980.00"));
        assertThat(series.amountRange().max()).isEqualTo(brl("2000.00"));
        assertThat(series.occurrences()).hasSize(4);
        assertThat(series.algorithmVersion()).isEqualTo("forecast-v1");
        assertThat(series.status()).isEqualTo(SeriesStatus.DETECTED);
        assertThat(series.origin()).isEqualTo(SeriesOrigin.DETECTED);
        assertThat(series.userExpectedAmount()).isNull();
        assertThat(series.userExpectedDay()).isNull();
    }

    @Test
    void evenMedianRoundsHalfEven() {
        // 100,01 e 100,02 → 100,015 → 100,02 (HALF_EVEN para o par); 100,02 e 100,03 → 100,025 → 100,02
        assertThat(Statistics.median(List.of(brl("100.01"), brl("100.02")))).isEqualTo(brl("100.02"));
        assertThat(Statistics.median(List.of(brl("100.02"), brl("100.03")))).isEqualTo(brl("100.02"));
    }

    @Test
    void monthsWithMoreThanOneChargeDoNotCount() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "streamo", 10, "SUBSCRIPTIONS", "2026-04=40.00", "2026-05=40.00",
                        "2026-06=40.00")
                // maio e junho com uma segunda cobrança: só abril sobra com exatamente uma → menos de 3 meses
                .add("2026-05-20", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-06-20", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00");
        assertThat(detect(fixture)).isEmpty();

        // com 4 meses de uma cobrança e um quinto com duas, ainda há 4 meses válidos
        ForecastFixture withExtra = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "streamo", 10, "SUBSCRIPTIONS", "2026-03=40.00", "2026-04=40.00",
                        "2026-05=40.00", "2026-06=40.00", "2026-07=40.00")
                .add("2026-07-20", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00");
        assertThat(detect(withExtra)).singleElement().satisfies(series -> {
            assertThat(series.occurrences()).hasSize(4);
            assertThat(series.confidence()).isEqualTo(Confidence.MEDIUM);
        });
    }

    @Test
    void expenseWithoutMerchantIsNeverRecurring() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, null, 10, "OTHER_EXPENSE", "2026-04=50.00", "2026-05=50.00",
                        "2026-06=50.00");
        assertThat(detect(fixture)).isEmpty();
    }

    @Test
    void incomeWithoutMerchantUsesTheCategoryAsKey() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.INCOME, null, 5, "SALARY", "2026-04=3000.00", "2026-05=3000.00",
                        "2026-06=3000.00");
        assertThat(detect(fixture)).singleElement().satisfies(series -> {
            assertThat(series.direction()).isEqualTo(SeriesDirection.INCOME);
            assertThat(series.key()).isEqualTo("category:SALARY");
        });
    }

    @Test
    void cardInstallmentsNeverBecomeRecurring() {
        ForecastFixture fixture = new ForecastFixture();
        UUID card = ForecastFixture.AURORA;
        for (String month : List.of("2026-04", "2026-05", "2026-06", "2026-07")) {
            fixture.addCard(month + "-12", "ELECTRONICS", "loja tech", "480.00", card, true);
        }
        assertThat(detect(fixture)).isEmpty();
    }

    @Test
    void refundsAndEntriesOutsideTheWindowAreIgnored() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.REFUND, "loja", 10, "SHOPPING", "2026-04=80.00", "2026-05=80.00",
                        "2026-06=80.00")
                // fev e a mês corrente (set) ficam fora da janela mar–ago
                .monthly(CashFlowRole.EXPENSE, "ginasio", 3, "HEALTH", "2026-01=90.00", "2026-02=90.00",
                        "2026-09=90.00");
        assertThat(detect(fixture)).isEmpty();
    }

    @Test
    void expectedDayIsTheMostFrequentAndTiesPickTheSmallest() {
        ForecastFixture fixture = new ForecastFixture()
                .add("2026-04-12", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-05-11", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-06-12", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-07-11", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-08-12", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00");
        assertThat(detect(fixture).getFirst().expectedDay()).isEqualTo(12);

        ForecastFixture tie = new ForecastFixture()
                .add("2026-04-12", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-05-11", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-06-12", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00")
                .add("2026-07-11", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00");
        assertThat(detect(tie).getFirst().expectedDay()).isEqualTo(11);
    }

    @Test
    void channelIsTheMostFrequentAccountOrCard() {
        ForecastFixture fixture = new ForecastFixture();
        fixture.add("2026-04-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00",
                Channel.card(ForecastFixture.AURORA), false);
        fixture.add("2026-05-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00",
                Channel.card(ForecastFixture.AURORA), false);
        fixture.add("2026-06-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00",
                Channel.account(ForecastFixture.ACCOUNT), false);
        assertThat(detect(fixture).getFirst().channel()).isEqualTo(Channel.card(ForecastFixture.AURORA));
    }

    @Test
    void detectionIsDeterministicAndOrdered() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "zeta", 10, "OTHER_EXPENSE", "2026-04=50.00", "2026-05=50.00",
                        "2026-06=50.00")
                .monthly(CashFlowRole.EXPENSE, "alfa", 12, "OTHER_EXPENSE", "2026-04=60.00", "2026-05=60.00",
                        "2026-06=60.00")
                .monthly(CashFlowRole.INCOME, "empresa", 5, "SALARY", "2026-04=5000.00", "2026-05=5000.00",
                        "2026-06=5000.00");
        List<RecurringSeries> first = detect(fixture);
        assertThat(first).isEqualTo(detect(fixture));
        // INCOME antes de EXPENSE (ordem do enum), depois pela chave
        assertThat(first).extracting(RecurringSeries::key)
                .containsExactly("merchant:empresa", "merchant:alfa", "merchant:zeta");
    }
}
