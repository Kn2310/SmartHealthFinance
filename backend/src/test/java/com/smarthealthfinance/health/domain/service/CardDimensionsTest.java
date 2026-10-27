package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.health.domain.enums.DimensionStatus;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.model.NextStep;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Credit e Debt do {@code health-v2} (ADR-0027) com os cartões de 09-dados-de-exemplo lidos pela porta real de Cards,
 * em 22 set 2026. A renda média de abr–ago é R$ 6.560,00.
 */
class CardDimensionsTest {

    private static DimensionResult dimension(HealthResult result, HealthDimension dimension) {
        return result.dimensions().stream().filter(d -> d.dimension() == dimension).findFirst().orElseThrow();
    }

    private static BigDecimal evidence(DimensionResult dimension, String code) {
        return dimension.evidence().stream().filter(e -> e.code().equals(code)).map(Evidence::value).findFirst()
                .orElseThrow();
    }

    private static HealthResult calculate(AuroraCards cards) {
        return HealthCalculator.calculate(HealthFixture.example().cards(cards).asOf("2026-09-22"));
    }

    /** U = 6.772,80 ÷ 12.000 = 0,5644 → S = 57,97; p = 3/3 → 0,7 · 57,97 + 30 = 70,58 → 71, como no design. */
    @Test
    void creditOfAuroraAlone() {
        DimensionResult credit = dimension(calculate(AuroraCards.paidOnTime()), HealthDimension.CREDIT);

        assertThat(credit.status()).isEqualTo(DimensionStatus.OK);
        assertThat(credit.score()).isEqualTo(71);
        assertThat(credit.attention()).isTrue();
        assertThat(credit.explanation()).isEqualTo("Utilização de 56% do limite do Aurora Platinum, incluindo "
                + "R$ 3.360,00 de parcelas futuras. 3 de 3 faturas pagas integralmente até o vencimento.");
        assertThat(evidence(credit, "utilized")).isEqualByComparingTo("6772.80");
        assertThat(evidence(credit, "utilization")).isEqualByComparingTo("56.44");
        assertThat(evidence(credit, "dueInvoices")).isEqualByComparingTo("3");
        assertThat(evidence(credit, "paidOnTime")).isEqualByComparingTo("3");
    }

    /** U = 7.793,20 ÷ 18.000 = 0,4330 → S = 68,92 → 0,7 · 68,92 + 30 = 78,24 → 78; abaixo de 50%, sem atenção. */
    @Test
    void creditIsTheAggregateUtilizationOfTheActiveCards() {
        DimensionResult credit = dimension(calculate(AuroraCards.paidOnTime().withHorizonte()),
                HealthDimension.CREDIT);

        assertThat(credit.score()).isEqualTo(78);
        assertThat(credit.attention()).isFalse();
        assertThat(credit.explanation()).isEqualTo("Utilização de 43% do limite dos seus 2 cartões (R$ 7.793,20 de "
                + "R$ 18.000,00), incluindo R$ 3.360,00 de parcelas futuras. 3 de 3 faturas pagas integralmente até o "
                + "vencimento.");
    }

    /** Setembro (efetivo 07 set) sem pagamento: p = 2/3 → 0,7 · S(U) + 20, e o primeiro passo é a fatura vencida. */
    @Test
    void anOverdueInvoiceWeighsAndComesFirstInTheNextSteps() {
        HealthResult result = calculate(AuroraCards.septemberUnpaid());
        DimensionResult credit = dimension(result, HealthDimension.CREDIT);

        // Utilizado = 6.772,80 + 3.012,10 = 9.784,90 → U = 0,8154 → S = 29,85 → 20,90 + 20 = 40,90 → 41.
        assertThat(credit.score()).isEqualTo(41);
        assertThat(credit.explanation()).endsWith("2 de 3 faturas pagas integralmente até o vencimento.");
        NextStep first = result.nextSteps().getFirst();
        assertThat(first.kind()).isEqualTo(NextStepKind.PAY_OVERDUE_INVOICE);
        assertThat(first.text()).isEqualTo("Fatura de set/2026 do Aurora Platinum vencida");
        assertThat(first.evidence()).isEqualTo("R$ 3.012,10 em aberto desde 05 set.");
        assertThat(first.month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(first.amount()).isEqualTo(Money.of("3012.10", HealthFixture.BRL));
        assertThat(first.cardId()).isNotNull();
    }

    /** b = 480 ÷ 6.560 = 0,0732 → 88,29; h = 3.360 ÷ 6.560 = 0,51 → 88,78; 0,7 · 88,29 + 0,3 · 88,78 = 88,44 → 88. */
    @Test
    void debtOfTheNotebook() {
        DimensionResult debt = dimension(calculate(AuroraCards.paidOnTime()), HealthDimension.DEBT);

        assertThat(debt.score()).isEqualTo(88);
        assertThat(debt.attention()).isFalse();
        assertThat(debt.explanation()).isEqualTo("Único compromisso: Loja Tech, 7 parcelas restantes (R$ 3.360,00 até "
                + "mai/2027). Próxima fatura: R$ 480,00 (7,3% da renda média).");
        assertThat(evidence(debt, "averageIncome")).isEqualByComparingTo("6560.00");
        assertThat(evidence(debt, "installmentShare")).isEqualByComparingTo("7.32");
        assertThat(evidence(debt, "incomeMonths")).isEqualByComparingTo("0.51");
    }

    @Test
    void withoutInstallmentsDebtIsFull() {
        AuroraCards cards = AuroraCards.paidOnTime();
        // Antes da compra do notebook (12 jul), o Aurora não tem parcelas a vencer.
        HealthResult june = HealthCalculator.calculate(HealthFixture.example().cards(cards)
                .closed("2026-06", "2026-09-22"));

        DimensionResult debt = dimension(june, HealthDimension.DEBT);
        assertThat(debt.score()).isEqualTo(100);
        assertThat(debt.explanation()).isEqualTo("Nenhum parcelamento em andamento nos seus cartões.");
    }

    @Test
    void withAllDimensionsTheScoreCoversTheWholeWeight() {
        HealthResult result = calculate(AuroraCards.paidOnTime());

        // (25·97 + 15·71 + 15·71 + 15·88 + 20·69 + 10·100) ÷ 100 = 82,55 → 83.
        assertThat(result.algorithmVersion()).isEqualTo("health-v2");
        assertThat(result.score()).isEqualTo(83);
        assertThat(result.coverage()).isEqualTo(new HealthResult.Coverage(6, 6, 100));
        assertThat(result.activeCards()).isEqualTo(1);
        assertThat(result.nextSteps()).extracting(NextStep::kind).containsExactly(NextStepKind.BUILD_RESERVE,
                NextStepKind.REVIEW_CATEGORY, NextStepKind.REVIEW_CARD_UTILIZATION);
        assertThat(result.nextSteps().getLast().text()).isEqualTo("Acompanhar a utilização do Aurora Platinum");
        assertThat(result.nextSteps().getLast().evidence())
                .isEqualTo("Sem as parcelas futuras, a utilização seria de 28%.");
    }

    @Test
    void withoutCardsCreditAndDebtStayNotApplicable() {
        HealthResult result = HealthCalculator.calculate(HealthFixture.example().asOf("2026-09-22"));

        assertThat(dimension(result, HealthDimension.CREDIT).status()).isEqualTo(DimensionStatus.NOT_APPLICABLE);
        assertThat(dimension(result, HealthDimension.DEBT).status()).isEqualTo(DimensionStatus.NOT_APPLICABLE);
        assertThat(result.score()).isEqualTo(84);
        assertThat(result.activeCards()).isZero();
    }
}
