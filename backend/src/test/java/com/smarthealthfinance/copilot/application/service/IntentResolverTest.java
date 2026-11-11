package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.CopilotCodec;
import com.smarthealthfinance.copilot.application.port.RequestCatalog;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** O modelo escolhe, o pipeline decide (ADR-0039 §4): parâmetro fora do catálogo vira nulo. */
class IntentResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final RequestCatalog.Catalog CATALOG = new RequestCatalog.Catalog(Currency.getInstance("BRL"),
            Map.of("GROCERIES", "Mercado", "DINING_OUT", "Alimentação fora"), List.of());

    private static CopilotCodec.RawIntent raw(String intent, String month, String category, String target,
                                              String amount, String deadline) {
        return new CopilotCodec.RawIntent(intent, month, category, target, amount, deadline, null, null);
    }

    @Test
    void unknownIntentInvalidatesTheOutput() {
        assertThat(IntentResolver.resolve(raw("PAY_BILL", null, null, null, null, null), CATALOG, new Pseudonyms(),
                TODAY)).isEmpty();
        assertThat(IntentResolver.resolve(raw(null, null, null, null, null, null), CATALOG, new Pseudonyms(),
                TODAY)).isEmpty();
    }

    @Test
    void keepsOnlyParametersThatExistInTheCatalogAndAreNotInTheFuture() {
        IntentDecision ok = IntentResolver.resolve(raw("CATEGORY", "2026-10", "GROCERIES", null, null, null),
                CATALOG, new Pseudonyms(), TODAY).orElseThrow();
        IntentDecision bad = IntentResolver.resolve(raw("CATEGORY", "2026-12", "CASINO", "cartao_1", null, null),
                CATALOG, new Pseudonyms(), TODAY).orElseThrow();

        assertThat(ok.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(ok.category()).isEqualTo("GROCERIES");
        assertThat(bad.month()).isNull();
        assertThat(bad.category()).isNull();
        assertThat(bad.target()).as("intenção sem alvo").isNull();
        assertThat(IntentResolver.resolve(raw("CASH_FLOW", "outubro", null, null, null, null), CATALOG,
                new Pseudonyms(), TODAY).orElseThrow().month()).isNull();
    }

    @Test
    void targetMustBeAKnownItemOfTheRightKindOrTheOnlyOne() {
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.GOAL, "g1", "PURCHASE", "Viagem");
        pseudonyms.assign(PseudonymKind.GOAL, "g2", "EMERGENCY_RESERVE", "Reserva");
        pseudonyms.assign(PseudonymKind.CARD, "c1", "VISA", "Nubank");

        assertThat(resolve("GOALS", "objetivo_2", pseudonyms).target()).isEqualTo("objetivo_2");
        assertThat(resolve("GOALS", "cartao_1", pseudonyms).target()).as("tipo errado, vários objetivos").isNull();
        assertThat(resolve("GOALS", null, pseudonyms).target()).isNull();
        assertThat(resolve("CARDS", null, pseudonyms).target()).as("único cartão").isEqualTo("cartao_1");
        assertThat(resolve("CARDS", "cartao_7", pseudonyms).target()).isEqualTo("cartao_1");
    }

    @Test
    void scenarioOnlyForSimulationWithPositivePlainAmountsAndFutureDeadline() {
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.GOAL, "g1", "PURCHASE", "Viagem");

        IntentDecision simulation = IntentResolver.resolve(raw("GOAL_SIMULATION", null, null, "objetivo_1",
                "500.00", "2027-06"), CATALOG, pseudonyms, TODAY).orElseThrow();
        assertThat(simulation.scenario()).isEqualTo(new IntentDecision.Scenario("500.00", "2027-06", null, null));

        assertThat(IntentResolver.resolve(raw("GOAL_SIMULATION", null, null, "objetivo_1", "R$ 500", "2026-01"),
                CATALOG, pseudonyms, TODAY).orElseThrow().scenario()).as("tudo inválido").isNull();
        assertThat(IntentResolver.resolve(raw("GOAL_SIMULATION", null, null, null, "-5", null), CATALOG,
                pseudonyms, TODAY).orElseThrow().scenario()).isNull();
        assertThat(IntentResolver.resolve(raw("GOALS", null, null, null, "500.00", null), CATALOG, pseudonyms,
                TODAY).orElseThrow().scenario()).as("só na simulação").isNull();
    }

    private static IntentDecision resolve(String intent, String target, Pseudonyms pseudonyms) {
        IntentDecision decision = IntentResolver.resolve(raw(intent, null, null, target, null, null), CATALOG,
                pseudonyms, TODAY).orElseThrow();
        assertThat(decision.intent()).isEqualTo(Intent.valueOf(intent));
        return decision;
    }
}
