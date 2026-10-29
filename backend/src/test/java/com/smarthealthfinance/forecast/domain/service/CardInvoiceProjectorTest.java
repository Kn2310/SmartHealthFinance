package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.InvoiceProjectionKind;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.model.CardInvoiceProjection;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.service.CardInvoiceProjector.Result;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.AURORA;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Faturas de cartão projetadas (ADR-0030 §3.5), com o exemplo da Aurora de 09-dados-de-exemplo. */
class CardInvoiceProjectorTest {

    private static ProjectionContext context(ForecastInput input) {
        List<RecurringSeries> detected = RecurrenceDetector.detect(input.entries(), input.today());
        List<RecurringSeries> merged = RecurrenceMerge.merge(input.existingSeries(), detected);
        return ProjectionContext.of(input, SeriesAssessor.assess(merged, detected, input.entries(), input.today()));
    }

    /** Compras no cartão nas janelas "depois do dia equivalente a hoje" das três faturas fechadas. */
    private static ForecastFixture purchases() {
        return new ForecastFixture()
                .addCard("2026-06-24", "GROCERIES", "loja a", "100.00", AURORA, false)
                .addCard("2026-07-26", "GROCERIES", "loja b", "80.00", AURORA, false)
                .addCard("2026-08-25", "GROCERIES", "loja c", "120.00", AURORA, false)
                // fora da janela (antes do dia equivalente), parcelada e de outro cartão: não contam
                .addCard("2026-08-10", "GROCERIES", "loja d", "300.00", AURORA, false)
                .addCard("2026-08-26", "ELECTRONICS", "loja tech", "480.00", AURORA, true)
                .addCard("2026-08-26", "GROCERIES", "loja e", "999.00", ForecastFixture.OTHER_ACCOUNT, false);
    }

    private static Result project(ForecastFixture fixture, String firstActivity, CardPosition... cards) {
        ForecastInput input = fixture.input("2026-09-22", firstActivity, "18420.35", List.of(), List.of(cards),
                List.of());
        return CardInvoiceProjector.project(context(input), CashFlowProjector.horizonEnd(date("2026-09-22")));
    }

    private static CardInvoiceProjection find(Result result, InvoiceProjectionKind kind, YearMonth closing) {
        return result.projections().stream()
                .filter(p -> p.kind() == kind && p.closingMonth().equals(closing))
                .findFirst().orElseThrow();
    }

    @Test
    void auroraNovemberIsTheCommitted480PlusTheMedianWithoutInstallments() {
        Result result = project(purchases(), "2026-03-01", ForecastFixture.aurora());
        CardInvoiceProjection november = find(result, InvoiceProjectionKind.NEXT, YearMonth.of(2026, 10));

        // vence 05/nov; medianas das faturas sem parcelas: 2.710,40 · 2.474,10 · 2.532,10 → 2.532,10
        assertThat(november.dueDate()).isEqualTo(date("2026-11-05"));
        assertThat(november.total().basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(november.total().factPart()).isEqualTo(brl("480.00"));
        assertThat(november.total().estimatedPart()).isEqualTo(brl("2532.10"));
        assertThat(november.total().value()).isEqualTo(brl("3012.10"));
        // faixa: comprometido + [mín, máx] das faturas sem parcelas
        assertThat(november.total().low()).isEqualTo(brl("2954.10"));
        assertThat(november.total().high()).isEqualTo(brl("3190.40"));
        assertThat(november.total().confidence()).isEqualTo(Confidence.HIGH);
        assertThat(november.total().limitations()).isEmpty();
        // evidências: as três faturas de base com o valor sem parcelas
        assertThat(november.total().evidence()).hasSize(3).allSatisfy(e -> assertThat(e.refId()).isEqualTo(AURORA));
        assertThat(november.total().evidence()).extracting(e -> e.value().toPlainString())
                .containsExactly("2710.40", "2474.10", "2532.10");
    }

    @Test
    void theLayoutAverageWithInstallmentsWouldCountTheInstallmentsTwice() {
        // média das faturas totais = 2.892,20; somada ao comprometido daria 3.372,20 (parcelas contadas duas vezes)
        Money average = brl("2710.40").plus(brl("2954.10")).plus(brl("3012.10")).minus(brl("0"));
        assertThat(average.amount().divide(java.math.BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_EVEN))
                .isEqualByComparingTo("2892.20");
        CardInvoiceProjection november = find(project(purchases(), "2026-03-01", ForecastFixture.aurora()),
                InvoiceProjectionKind.NEXT, YearMonth.of(2026, 10));
        assertThat(november.total().value().amount()).isNotEqualByComparingTo("3372.20");
    }

    @Test
    void futureInvoicesGoUntilTheEndOfTheHorizon() {
        Result result = project(purchases(), "2026-03-01", ForecastFixture.aurora());

        // hoje 22/09: horizonte até 31/12 → abertura (vence 05/out), próxima (05/nov) e futura (05/dez); a de 05/jan sai
        assertThat(result.projections()).extracting(CardInvoiceProjection::kind)
                .containsExactly(InvoiceProjectionKind.OPEN, InvoiceProjectionKind.NEXT, InvoiceProjectionKind.FUTURE);
        CardInvoiceProjection december = find(result, InvoiceProjectionKind.FUTURE, YearMonth.of(2026, 11));
        assertThat(december.dueDate()).isEqualTo(date("2026-12-05"));
        assertThat(december.total().value()).isEqualTo(brl("3012.10"));
    }

    @Test
    void theOpenInvoiceIsAFactPartialPlusNewPurchasesUntilClosing() {
        Result result = project(purchases(), "2026-03-01", ForecastFixture.aurora());
        CardInvoiceProjection open = find(result, InvoiceProjectionKind.OPEN, YearMonth.of(2026, 9));

        // parcial (fato) 3.412,80 + mediana das compras sem parcela depois do dia equivalente a hoje [100, 80, 120]
        assertThat(open.closingDate()).isEqualTo(date("2026-09-28"));
        assertThat(open.total().factPart()).isEqualTo(brl("3412.80"));
        assertThat(open.total().estimatedPart()).isEqualTo(brl("100.00"));
        assertThat(open.total().value()).isEqualTo(brl("3512.80"));
        assertThat(open.total().low()).isEqualTo(brl("3492.80"));
        assertThat(open.total().high()).isEqualTo(brl("3532.80"));
        assertThat(open.total().basis()).isEqualTo(Basis.ESTIMATED);
    }

    @Test
    void paymentsLeaveTheAccountOnTheDueDate() {
        Result result = project(purchases(), "2026-03-01", ForecastFixture.aurora());

        assertThat(result.payments()).extracting(p -> p.date().toString())
                .containsExactly("2026-10-05", "2026-11-05", "2026-12-05");
        assertThat(result.payments().get(1).amount().factPart()).isEqualTo(brl("480.00"));
        assertThat(result.payments().get(1).amount().estimatedPart()).isEqualTo(brl("2532.10"));
    }

    @Test
    void creditsReduceTheNewPurchasesAndInstallmentsAreExcluded() {
        Money zero = brl("0");
        assertThat(CardInvoiceProjector.newPurchases(List.of(ForecastFixture.charge("2000.00"),
                ForecastFixture.installment(2, 10, "480.00"), ForecastFixture.credit("150.00")), zero))
                .isEqualTo(brl("1850.00"));
        // "1 de 1" não é parcela
        assertThat(CardInvoiceProjector.newPurchases(List.of(ForecastFixture.installment(1, 1, "90.00")), zero))
                .isEqualTo(brl("90.00"));
    }

    @Test
    void lessThanTwoClosedInvoicesMeansNoEstimateOfNewPurchases() {
        CardPosition aurora = ForecastFixture.aurora();
        List<InvoiceStatement> onlyOneClosed = new ArrayList<>(aurora.statements().stream()
                .filter(s -> s.status() != InvoiceStatus.PAID || s.dates().closingMonth().equals(YearMonth.of(2026, 8)))
                .toList());
        CardPosition young = new CardPosition(aurora.cardId(), aurora.nickname(), aurora.last4(), aurora.limit(),
                aurora.asOf(), aurora.utilization(), aurora.current(), aurora.nextInvoiceCommitted(), onlyOneClosed,
                aurora.commitments(), aurora.dueInvoices(), aurora.lines());

        Result result = project(purchases(), "2026-03-01", young);

        for (CardInvoiceProjection projection : result.projections()) {
            assertThat(projection.total().basis()).isEqualTo(Basis.FACT);
            assertThat(projection.total().limitations()).containsExactly(Limitation.PARTIAL_CARD_HISTORY);
        }
        CardInvoiceProjection open = find(result, InvoiceProjectionKind.OPEN, YearMonth.of(2026, 9));
        assertThat(open.total().value()).isEqualTo(brl("3412.80"));
        assertThat(open.total().partial()).isTrue();
        assertThat(find(result, InvoiceProjectionKind.NEXT, YearMonth.of(2026, 10)).total().value())
                .isEqualTo(brl("480.00"));
    }

    @Test
    void withoutEnoughGlobalHistoryOnlyTheFactsAreShown() {
        Result result = project(purchases(), "2026-08-01", ForecastFixture.aurora());

        assertThat(result.projections()).allSatisfy(p -> {
            assertThat(p.total().basis()).isEqualTo(Basis.FACT);
            assertThat(p.total().limitations()).containsExactly(Limitation.INSUFFICIENT_HISTORY);
        });
    }

    @Test
    void closedInvoicesStillOwedAreFactsLeavingOnTheirDueDate() {
        CardPosition aurora = ForecastFixture.aurora();
        List<InvoiceStatement> statements = new ArrayList<>(aurora.statements());
        // fatura de ago (vence 05/set, já passou) segue em aberto: 1.000,00 → sai amanhã; sem atraso nos demais
        statements.set(2, ForecastFixture.statement(YearMonth.of(2026, 8), InvoiceStatus.OVERDUE, "3012.10",
                "1000.00"));
        CardPosition overdue = new CardPosition(aurora.cardId(), aurora.nickname(), aurora.last4(), aurora.limit(),
                aurora.asOf(), aurora.utilization(), aurora.current(), aurora.nextInvoiceCommitted(), statements,
                aurora.commitments(), aurora.dueInvoices(), aurora.lines());

        Result result = project(purchases(), "2026-03-01", overdue);

        assertThat(result.payments().getFirst().date()).isEqualTo(LocalDate.parse("2026-09-23"));
        assertThat(result.payments().getFirst().amount().basis()).isEqualTo(Basis.FACT);
        assertThat(result.payments().getFirst().amount().value()).isEqualTo(brl("1000.00"));
    }
}
