package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.CopilotFixtures;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.Notice;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.DataUsedItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Avisos e "Dados usados" (ADR-0040 §3): derivados do contexto e da validação, nunca do modelo. */
class AnswerNoticesAndDataUsedTest {

    private static DataQuality quality(boolean partial, DataFreshness freshness) {
        return new DataQuality(6, partial, List.of(), freshness, Set.of("OFX"));
    }

    private static CopilotContext context(Intent intent, DataQuality quality, ContextValue... values) {
        return new CopilotContext(intent, CopilotFixtures.TODAY, CopilotFixtures.BRL, List.of(values), quality,
                List.of(), List.of());
    }

    private static ContextValue value(String ref, ValueKind kind, String period) {
        return new ContextValue(ref, "Valor", "10", ValueUnit.MONEY, kind, period, "test-v1");
    }

    @Test
    void noticesFollowTheContextAndTheValidationInAFixedOrder() {
        CopilotContext everything = context(Intent.CARDS, quality(true, DataFreshness.UPDATING),
                value("v1", ValueKind.ESTIMATED, null), value("v2", ValueKind.SIMULATED, null));

        assertThat(AnswerNotices.of(everything, ValidationStatus.SAFE_MODE)).containsExactly(Notice.PARTIAL_PERIOD,
                Notice.ESTIMATED, Notice.SIMULATED, Notice.SAFE_MODE, Notice.UPDATING);
        assertThat(AnswerNotices.of(everything, ValidationStatus.VALID)).doesNotContain(Notice.SAFE_MODE);
    }

    @Test
    void aCleanContextHasNoNotices() {
        CopilotContext clean = context(Intent.CARDS, quality(false, DataFreshness.UP_TO_DATE),
                value("v1", ValueKind.FACT, null));

        assertThat(AnswerNotices.of(clean, ValidationStatus.REGENERATED)).isEmpty();
    }

    @Test
    void dataUsedIsAReadableLabelWithThePeriodSpanAndThePartialFlag() {
        CopilotContext single = context(Intent.CARDS, quality(true, DataFreshness.UP_TO_DATE),
                value("v1", ValueKind.FACT, "2026-10"), value("v2", ValueKind.FACT, "2026-10"));
        CopilotContext range = context(Intent.CASH_FLOW, quality(false, DataFreshness.UP_TO_DATE),
                value("v1", ValueKind.FACT, "2026-06-01..2026-06-30"), value("v2", ValueKind.FACT, "2026-11"),
                value("v3", ValueKind.FACT, "2026-09"));
        CopilotContext noPeriod = context(Intent.GOALS, quality(false, DataFreshness.UP_TO_DATE),
                value("v1", ValueKind.FACT, null));

        assertThat(DataUsedLabels.of(single)).containsExactly(new DataUsedItem("Fatura out/2026", true));
        assertThat(DataUsedLabels.of(range))
                .containsExactly(new DataUsedItem("Fluxo de caixa jun/2026 a nov/2026", false));
        assertThat(DataUsedLabels.of(noPeriod)).containsExactly(new DataUsedItem("Objetivos", false));
    }
}
