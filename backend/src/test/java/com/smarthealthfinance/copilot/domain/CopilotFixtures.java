package com.smarthealthfinance.copilot.domain;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.SourceRef;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Set;

/** Contexto de cartões com um valor de cada tipo e unidade, para os testes do domínio do Copilot. */
public final class CopilotFixtures {

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    public static final Currency BRL = Currency.getInstance("BRL");
    public static final String CARD_ID = "0b8f8a5e-4a3c-4d36-9a52-2f4a0e3c9d11";
    public static final String CARD_NICKNAME = "Roxinho da Ana 4321";

    private CopilotFixtures() {
    }

    public static Pseudonyms pseudonyms() {
        Pseudonyms pseudonyms = new Pseudonyms();
        pseudonyms.assign(PseudonymKind.CARD, CARD_ID, "VISA", CARD_NICKNAME);
        return pseudonyms;
    }

    public static CopilotContext context() {
        return context(new DataQuality(6, false, List.of(), DataFreshness.UP_TO_DATE, Set.of("OFX")));
    }

    public static CopilotContext context(DataQuality quality) {
        return new CopilotContext(Intent.CARDS, TODAY, BRL, List.of(
                value("v1", "Fatura atual de cartao_1", "1234.5600", ValueUnit.MONEY, ValueKind.FACT),
                value("v2", "Vencimento da fatura de cartao_1", "2026-11-15", ValueUnit.DATE, ValueKind.FACT),
                value("v3", "Utilização do limite de cartao_1", "36.1", ValueUnit.PERCENT, ValueKind.FACT),
                value("v4", "Próxima fatura de cartao_1", "980.4", ValueUnit.MONEY, ValueKind.ESTIMATED),
                value("v5", "Mês da próxima fatura", "2026-12", ValueUnit.MONTH, ValueKind.FACT),
                value("v6", "Maior comerciante", "Padaria 24h", ValueUnit.TEXT, ValueKind.FACT),
                value("v7", "Parcelamentos ativos", "3", ValueUnit.COUNT, ValueKind.FACT)),
                quality, List.of(new SourceRef("GetCardsSummary", "cards-v1")),
                List.of(new OfferedAction(ActionKind.OPEN_INVOICE, "cartao_1"),
                        new OfferedAction(ActionKind.OPEN_FORECAST, null)));
    }

    public static ContextValue value(String ref, String label, String value, ValueUnit unit, ValueKind kind) {
        return new ContextValue(ref, label, value, unit, kind, null, "cards-v1");
    }
}
