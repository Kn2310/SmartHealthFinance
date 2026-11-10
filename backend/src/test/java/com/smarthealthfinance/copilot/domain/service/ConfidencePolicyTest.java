package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.CopilotFixtures;
import com.smarthealthfinance.copilot.domain.enums.AnswerConfidence;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Regra {@code confidence-v1} (ADR-0039 §6): vem só da qualidade dos dados. */
class ConfidencePolicyTest {

    @Test
    void lowWhenReadModelIsNotBuiltOrHistoryIsShort() {
        assertThat(of(quality(6, false, List.of(), DataFreshness.NOT_BUILT), false)).isEqualTo(AnswerConfidence.LOW);
        assertThat(of(quality(1, false, List.of(), DataFreshness.UP_TO_DATE), false)).isEqualTo(AnswerConfidence.LOW);
    }

    @Test
    void mediumWhenAnythingWeakensTheData() {
        assertThat(of(quality(6, false, List.of(), DataFreshness.UPDATING), false))
                .isEqualTo(AnswerConfidence.MEDIUM);
        assertThat(of(quality(6, true, List.of(), DataFreshness.UP_TO_DATE), false))
                .isEqualTo(AnswerConfidence.MEDIUM);
        assertThat(of(quality(6, false, List.of("MISSING_INVOICE"), DataFreshness.UP_TO_DATE), false))
                .isEqualTo(AnswerConfidence.MEDIUM);
        assertThat(of(quality(2, false, List.of(), DataFreshness.UP_TO_DATE), false))
                .isEqualTo(AnswerConfidence.MEDIUM);
        assertThat(of(quality(6, false, List.of(), DataFreshness.UP_TO_DATE), true))
                .isEqualTo(AnswerConfidence.MEDIUM);
    }

    @Test
    void highOnlyWithCompleteFreshFacts() {
        assertThat(of(quality(6, false, List.of(), DataFreshness.UP_TO_DATE), false))
                .isEqualTo(AnswerConfidence.HIGH);
        assertThat(of(quality(null, false, List.of(), DataFreshness.UP_TO_DATE), false))
                .as("meses com dados não se aplicam").isEqualTo(AnswerConfidence.HIGH);
    }

    @Test
    void mergeKeepsTheWorstOfBoth() {
        DataQuality merged = quality(6, false, List.of("B"), DataFreshness.UP_TO_DATE)
                .merge(quality(3, true, List.of("A"), DataFreshness.UPDATING));

        assertThat(merged.monthsWithData()).isEqualTo(3);
        assertThat(merged.partialPeriod()).isTrue();
        assertThat(merged.gaps()).containsExactly("A", "B");
        assertThat(merged.freshness()).isEqualTo(DataFreshness.UPDATING);
        assertThat(DataQuality.EMPTY.merge(merged)).isEqualTo(merged);
    }

    private static DataQuality quality(Integer months, boolean partial, List<String> gaps, DataFreshness freshness) {
        return new DataQuality(months, partial, gaps, freshness, Set.of());
    }

    private static AnswerConfidence of(DataQuality quality, boolean estimated) {
        CopilotContext base = CopilotFixtures.context(quality);
        CopilotContext context = new CopilotContext(base.intent(), base.today(), base.currency(),
                base.values().stream().filter(value -> estimated || value.kind() == ValueKind.FACT).toList(),
                quality, base.sources(), base.actions());
        if (estimated) {
            assertThat(context.values()).anyMatch(value -> value.kind() == ValueKind.ESTIMATED
                    && value.unit() == ValueUnit.MONEY);
        }
        return ConfidencePolicy.of(context);
    }
}
